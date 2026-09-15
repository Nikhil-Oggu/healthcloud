package com.healthcloud.adjudication;

import com.healthcloud.claim.Claim;
import com.healthcloud.claim.ClaimLine;
import com.healthcloud.claim.ClaimLineRepository;
import com.healthcloud.claim.ClaimRepository;
import com.healthcloud.claim.ClaimStatus;
import com.healthcloud.claim.ClaimStatusHistory;
import com.healthcloud.claim.ClaimStatusHistoryRepository;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.coverage.CoveragePlan;
import com.healthcloud.coverage.CoveragePlanRepository;
import com.healthcloud.coverage.PatientEligibility;
import com.healthcloud.coverage.PatientEligibilityRepository;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientAccessGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The basic synthetic claims-adjudication engine (source-of-truth §Phase 5). It turns an ACCEPTED claim into a
 * deterministic, explainable {@link Adjudication}: it finds the coverage in effect on the claim's service date
 * (via {@link PatientEligibilityRepository#findCovering}), applies the plan's parameters through the pure
 * {@link AdjudicationCalculator}, and records a per-line breakdown plus claim totals — the §60 proof (for any
 * decision, which plan applied and how every amount was computed).
 *
 * <p>Adjudication is <b>owned by this engine command</b>, not a bare status change (like {@code ASSIGNED} on a
 * service request): {@code POST /api/v1/claims/{id}/adjudicate} advances ACCEPTED → ADJUDICATED and writes the
 * adjudication + a claim status-history row in <b>one transaction</b> (§31.6). A second attempt fails the
 * ACCEPTED gate (the claim is now ADJUDICATED), so the state gate is the double-apply safety. The whole pipeline
 * passes the layered authorization (§21): tenant → function/role (CLAIMS_REVIEWER/ORG_ADMIN) → object/relationship
 * ({@link PatientAccessGuard}, via the claim's patient).
 */
@Service
@Transactional(readOnly = true)
public class AdjudicationService {

    /** Roles allowed to run the adjudication engine — the reviewer's action (mirrors accept/reject). */
    private static final String[] ADJUDICATE_ROLES = {"CLAIMS_REVIEWER", "ORG_ADMIN"};

    private final ClaimRepository claims;
    private final ClaimLineRepository claimLines;
    private final ClaimStatusHistoryRepository claimStatusHistory;
    private final CoveragePlanRepository coveragePlans;
    private final PatientEligibilityRepository eligibility;
    private final AdjudicationRepository adjudications;
    private final AdjudicationLineRepository adjudicationLines;
    private final BenefitAccumulatorRepository accumulators;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public AdjudicationService(ClaimRepository claims, ClaimLineRepository claimLines,
                               ClaimStatusHistoryRepository claimStatusHistory,
                               CoveragePlanRepository coveragePlans, PatientEligibilityRepository eligibility,
                               AdjudicationRepository adjudications, AdjudicationLineRepository adjudicationLines,
                               BenefitAccumulatorRepository accumulators,
                               PatientAccessGuard accessGuard, UserContextAccessor userContext) {
        this.claims = claims;
        this.claimLines = claimLines;
        this.claimStatusHistory = claimStatusHistory;
        this.coveragePlans = coveragePlans;
        this.eligibility = eligibility;
        this.adjudications = adjudications;
        this.adjudicationLines = adjudicationLines;
        this.accumulators = accumulators;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * Adjudicate an ACCEPTED claim. Check order: role → exists + patient gate → ACCEPTED state → find coverage →
     * compute → persist adjudication + lines, advance the claim to ADJUDICATED, and append a status-history row,
     * all in one transaction. Returns the explainable result.
     */
    @Transactional
    public AdjudicationDto adjudicate(UUID claimId) {
        userContext.requireAnyRole(ADJUDICATE_ROLES);
        UserContext caller = userContext.requireUser();
        Claim claim = requireAccessibleClaim(claimId);

        // Only an ACCEPTED claim can be adjudicated (a second attempt fails here → 409, the double-apply safety).
        if (claim.getStatus() != ClaimStatus.ACCEPTED) {
            throw new InvalidStateTransitionException(
                    "Only an ACCEPTED claim can be adjudicated; this claim is " + claim.getStatus() + ".");
        }

        UUID organizationId = claim.getOrganizationId();
        List<ClaimLine> lines = claimLines
                .findByOrganizationIdAndClaimIdOrderByLineNumberAsc(organizationId, claimId);

        // Find the coverage in effect on the service date (0 or 1 row — periods are non-overlapping).
        List<PatientEligibility> covering =
                eligibility.findCovering(organizationId, claim.getPatientId(), claim.getServiceDate());
        PatientEligibility enrollment = covering.isEmpty() ? null : covering.get(0);

        Adjudication savedHeader;
        List<AdjudicationLine> savedLines;
        if (enrollment == null) {
            savedHeader = adjudications.save(buildDenial(claim, lines, caller));
            savedLines = saveDenialLines(claim, lines, savedHeader);
        } else {
            CoveragePlan plan = coveragePlans
                    .findByIdAndOrganizationId(enrollment.getCoveragePlanId(), organizationId)
                    .orElseThrow(NotFoundException::new);
            // §31: lock this patient/plan/year accumulator for the rest of the tx, so the deductible carried
            // across claims is read-and-updated without a lost update under concurrency.
            int benefitYear = claim.getServiceDate().getYear();
            BenefitAccumulator accumulator =
                    lockAccumulator(organizationId, claim.getPatientId(), plan.getId(), benefitYear);
            BigDecimal deductibleRemaining = plan.getDeductibleAmount().subtract(accumulator.getDeductibleMet());
            // Remaining out-of-pocket for the year (null plan max = no cap); the calculator caps member cost.
            BigDecimal oopRemaining = plan.getOutOfPocketMax() == null ? null
                    : plan.getOutOfPocketMax().subtract(accumulator.getOutOfPocketMet());
            AdjudicationCalculator.Computation computation =
                    compute(lines, plan, deductibleRemaining, oopRemaining);
            // Record this claim's contribution: deductible met + out-of-pocket accrued (the OOP-max hook).
            accumulator.add(deductibleApplied(computation), computation.totalMemberResponsibility());
            accumulators.save(accumulator);
            savedHeader = adjudications.save(
                    buildCovered(claim, plan, enrollment, computation, totalCharge(lines), caller));
            savedLines = saveCoveredLines(claim, lines, computation, savedHeader);
        }

        // §31.6: advance the claim and append its status-history row in the same transaction.
        ClaimStatus from = claim.getStatus();
        claim.setStatus(ClaimStatus.ADJUDICATED);
        claims.saveAndFlush(claim);
        claimStatusHistory.save(new ClaimStatusHistory(
                organizationId, claim.getId(), from, ClaimStatus.ADJUDICATED, caller.userId(),
                "Adjudicated by the adjudication engine (" + savedHeader.getOutcome() + ")",
                CorrelationId.current()));

        return AdjudicationDto.from(savedHeader, planName(organizationId, savedHeader.getCoveragePlanId()),
                savedLines);
    }

    /** The stored adjudication for a claim, tenant + relationship gated (secure 404 if unreachable or none). */
    public AdjudicationDto getByClaim(UUID claimId) {
        Claim claim = requireAccessibleClaim(claimId);
        Adjudication adjudication = adjudications
                .findByOrganizationIdAndClaimId(claim.getOrganizationId(), claimId)
                .orElseThrow(NotFoundException::new);
        List<AdjudicationLine> lines = adjudicationLines
                .findByOrganizationIdAndAdjudicationIdOrderByLineNumberAsc(
                        claim.getOrganizationId(), adjudication.getId());
        return AdjudicationDto.from(adjudication,
                planName(claim.getOrganizationId(), adjudication.getCoveragePlanId()), lines);
    }

    // --- covered path --------------------------------------------------------

    private Adjudication buildCovered(Claim claim, CoveragePlan plan, PatientEligibility enrollment,
                                      AdjudicationCalculator.Computation computation, BigDecimal totalCharge,
                                      UserContext caller) {
        return new Adjudication(
                claim.getOrganizationId(), claim.getId(), 1, AdjudicationOutcome.ADJUDICATED,
                plan.getId(), enrollment.getId(), totalCharge,
                computation.totalAllowed(), computation.totalPlanPaid(), computation.totalMemberResponsibility(),
                caller.userId(), CorrelationId.current());
    }

    private List<AdjudicationLine> saveCoveredLines(Claim claim, List<ClaimLine> lines,
                                                    AdjudicationCalculator.Computation computation,
                                                    Adjudication header) {
        List<AdjudicationLine> saved = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ClaimLine line = lines.get(i);
            AdjudicationCalculator.LineComputation c = computation.lines().get(i);
            saved.add(adjudicationLines.save(new AdjudicationLine(
                    claim.getOrganizationId(), header.getId(), line.getId(), line.getLineNumber(),
                    line.getProcedureCodeSystem(), line.getProcedureCode(), LineOutcome.COVERED,
                    line.getChargeAmount(), c.allowedAmount(), c.copayAmount(), c.deductibleAppliedAmount(),
                    c.coinsuranceAmount(), c.oopMaxAppliedAmount(), c.planPaidAmount(), c.memberResponsibility())));
        }
        return saved;
    }

    private AdjudicationCalculator.Computation compute(List<ClaimLine> lines, CoveragePlan plan,
                                                       BigDecimal deductibleRemaining, BigDecimal oopRemaining) {
        List<AdjudicationCalculator.LineCharge> charges = lines.stream()
                .map(l -> new AdjudicationCalculator.LineCharge(l.getLineNumber(), l.getChargeAmount()))
                .toList();
        return AdjudicationCalculator.adjudicate(
                new AdjudicationCalculator.PlanParameters(
                        plan.getDeductibleAmount(), plan.getCoinsuranceRate(), plan.getCopayAmount()),
                deductibleRemaining, oopRemaining, charges);
    }

    /** Ensure the accumulator row exists, then lock it FOR UPDATE for the rest of the transaction (§31). */
    private BenefitAccumulator lockAccumulator(UUID organizationId, UUID patientId, UUID planId, int benefitYear) {
        accumulators.insertIfAbsent(organizationId, patientId, planId, benefitYear);
        return accumulators.lockByKey(organizationId, patientId, planId, benefitYear)
                .orElseThrow(() -> new IllegalStateException("benefit accumulator missing after insert-if-absent"));
    }

    /** The total deductible this claim consumed — added to the accumulator. */
    private static BigDecimal deductibleApplied(AdjudicationCalculator.Computation computation) {
        return computation.lines().stream()
                .map(AdjudicationCalculator.LineComputation::deductibleAppliedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // --- denied path (no coverage on the service date) -----------------------

    private Adjudication buildDenial(Claim claim, List<ClaimLine> lines, UserContext caller) {
        BigDecimal totalCharge = totalCharge(lines);
        // No plan pays: the member is responsible for the full charge; allowed is 0 (nothing was covered).
        return new Adjudication(
                claim.getOrganizationId(), claim.getId(), 1, AdjudicationOutcome.DENIED_NO_ELIGIBILITY,
                null, null, totalCharge, money(BigDecimal.ZERO), money(BigDecimal.ZERO), totalCharge,
                caller.userId(), CorrelationId.current());
    }

    private List<AdjudicationLine> saveDenialLines(Claim claim, List<ClaimLine> lines, Adjudication header) {
        List<AdjudicationLine> saved = new ArrayList<>();
        for (ClaimLine line : lines) {
            BigDecimal charge = money(line.getChargeAmount());
            saved.add(adjudicationLines.save(new AdjudicationLine(
                    claim.getOrganizationId(), header.getId(), line.getId(), line.getLineNumber(),
                    line.getProcedureCodeSystem(), line.getProcedureCode(), LineOutcome.NOT_COVERED,
                    charge, money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO),
                    money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO), charge)));
        }
        return saved;
    }

    // --- helpers -------------------------------------------------------------

    /** Load a claim in the caller's tenant and confirm the caller may reach its patient (§21 layer 6), else 404. */
    private Claim requireAccessibleClaim(UUID claimId) {
        UUID organizationId = userContext.requireOrganizationId();
        Claim claim = claims.findByIdAndOrganizationId(claimId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(claim.getPatientId());
        return claim;
    }

    private String planName(UUID organizationId, UUID coveragePlanId) {
        if (coveragePlanId == null) {
            return null;
        }
        return coveragePlans.findByIdAndOrganizationId(coveragePlanId, organizationId)
                .map(CoveragePlan::getName)
                .orElse(null);
    }

    private static BigDecimal totalCharge(List<ClaimLine> lines) {
        return money(lines.stream().map(ClaimLine::getChargeAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
