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
import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coverage.CoveragePlan;
import com.healthcloud.coverage.CoveragePlanRepository;
import com.healthcloud.coverage.PatientEligibility;
import com.healthcloud.coverage.PatientEligibilityRepository;
import com.healthcloud.coverage.PlanExclusion;
import com.healthcloud.coverage.PlanExclusionRepository;
import com.healthcloud.coverage.PlanFeeScheduleEntry;
import com.healthcloud.coverage.PlanFeeScheduleRepository;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientAccessGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final PlanExclusionRepository planExclusions;
    private final PlanFeeScheduleRepository planFeeSchedule;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public AdjudicationService(ClaimRepository claims, ClaimLineRepository claimLines,
                               ClaimStatusHistoryRepository claimStatusHistory,
                               CoveragePlanRepository coveragePlans, PatientEligibilityRepository eligibility,
                               AdjudicationRepository adjudications, AdjudicationLineRepository adjudicationLines,
                               BenefitAccumulatorRepository accumulators, PlanExclusionRepository planExclusions,
                               PlanFeeScheduleRepository planFeeSchedule,
                               PatientAccessGuard accessGuard, UserContextAccessor userContext) {
        this.claims = claims;
        this.claimLines = claimLines;
        this.claimStatusHistory = claimStatusHistory;
        this.coveragePlans = coveragePlans;
        this.eligibility = eligibility;
        this.adjudications = adjudications;
        this.adjudicationLines = adjudicationLines;
        this.accumulators = accumulators;
        this.planExclusions = planExclusions;
        this.planFeeSchedule = planFeeSchedule;
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

            // A procedure excluded by the plan is NOT_COVERED — it skips the cost-sharing math and does not
            // touch the deductible or out-of-pocket max. Only covered lines flow through the calculator.
            Set<String> excluded = excludedCodeKeys(organizationId, plan.getId());
            List<ClaimLine> coveredLines = new ArrayList<>();
            List<ClaimLine> excludedLines = new ArrayList<>();
            for (ClaimLine line : lines) {
                if (excluded.contains(codeKey(line.getProcedureCodeSystem(), line.getProcedureCode()))) {
                    excludedLines.add(line);
                } else {
                    coveredLines.add(line);
                }
            }

            // §31: lock this patient/plan/year accumulator for the rest of the tx, so the deductible carried
            // across claims is read-and-updated without a lost update under concurrency.
            int benefitYear = claim.getServiceDate().getYear();
            BenefitAccumulator accumulator =
                    lockAccumulator(organizationId, claim.getPatientId(), plan.getId(), benefitYear);
            BigDecimal deductibleRemaining = plan.getDeductibleAmount().subtract(accumulator.getDeductibleMet());
            // Remaining out-of-pocket for the year (null plan max = no cap); the calculator caps member cost.
            BigDecimal oopRemaining = plan.getOutOfPocketMax() == null ? null
                    : plan.getOutOfPocketMax().subtract(accumulator.getOutOfPocketMet());
            // The plan's fee schedule (if any) prices covered lines: allowed = min(charge, fee-schedule amount).
            Map<String, BigDecimal> feeSchedule = feeScheduleByCode(organizationId, plan.getId());
            AdjudicationCalculator.Computation computation =
                    compute(coveredLines, plan, deductibleRemaining, oopRemaining, feeSchedule);
            // Record this claim's contribution: deductible met + out-of-pocket accrued (covered lines only).
            accumulator.add(deductibleApplied(computation), computation.totalMemberResponsibility());
            accumulators.save(accumulator);

            // Header totals: the covered math, plus excluded charges the member owes in full (plan pays 0).
            BigDecimal excludedCharge = totalCharge(excludedLines);
            savedHeader = adjudications.save(new Adjudication(
                    organizationId, claim.getId(), 1, AdjudicationOutcome.ADJUDICATED,
                    plan.getId(), enrollment.getId(), totalCharge(lines),
                    computation.totalAllowed(), computation.totalPlanPaid(),
                    computation.totalMemberResponsibility().add(excludedCharge),
                    caller.userId(), CorrelationId.current()));
            savedLines = saveCoveredAndExcludedLines(claim, lines, excluded, computation, savedHeader);
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

    /**
     * Persist one adjudication line per claim line, in order: an excluded procedure becomes a NOT_COVERED line
     * (member owes the full charge, plan pays 0); every other line is a COVERED line built from its computed
     * split. The covered computations are keyed by line number (the calculator only saw the covered lines).
     */
    private List<AdjudicationLine> saveCoveredAndExcludedLines(Claim claim, List<ClaimLine> lines,
                                                              Set<String> excluded,
                                                              AdjudicationCalculator.Computation computation,
                                                              Adjudication header) {
        Map<Integer, AdjudicationCalculator.LineComputation> byLine = new HashMap<>();
        for (AdjudicationCalculator.LineComputation c : computation.lines()) {
            byLine.put(c.lineNumber(), c);
        }

        List<AdjudicationLine> saved = new ArrayList<>();
        for (ClaimLine line : lines) {
            if (excluded.contains(codeKey(line.getProcedureCodeSystem(), line.getProcedureCode()))) {
                BigDecimal charge = money(line.getChargeAmount());
                saved.add(adjudicationLines.save(new AdjudicationLine(
                        claim.getOrganizationId(), header.getId(), line.getId(), line.getLineNumber(),
                        line.getProcedureCodeSystem(), line.getProcedureCode(), LineOutcome.NOT_COVERED,
                        charge, money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO),
                        money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO), charge)));
            } else {
                AdjudicationCalculator.LineComputation c = byLine.get(line.getLineNumber());
                saved.add(adjudicationLines.save(new AdjudicationLine(
                        claim.getOrganizationId(), header.getId(), line.getId(), line.getLineNumber(),
                        line.getProcedureCodeSystem(), line.getProcedureCode(), LineOutcome.COVERED,
                        line.getChargeAmount(), c.allowedAmount(), c.copayAmount(), c.deductibleAppliedAmount(),
                        c.coinsuranceAmount(), c.oopMaxAppliedAmount(), c.planPaidAmount(),
                        c.memberResponsibility())));
            }
        }
        return saved;
    }

    /** The set of procedure keys the plan excludes, as {@code SYSTEM|CODE} (canonical, matching claim lines). */
    private Set<String> excludedCodeKeys(UUID organizationId, UUID coveragePlanId) {
        Set<String> keys = new HashSet<>();
        for (PlanExclusion exclusion : planExclusions
                .findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(organizationId, coveragePlanId)) {
            keys.add(codeKey(exclusion.getCodeSystem(), exclusion.getCode()));
        }
        return keys;
    }

    private static String codeKey(CodeSystem system, String code) {
        return system.name() + "|" + code;
    }

    private AdjudicationCalculator.Computation compute(List<ClaimLine> lines, CoveragePlan plan,
                                                       BigDecimal deductibleRemaining, BigDecimal oopRemaining,
                                                       Map<String, BigDecimal> feeSchedule) {
        List<AdjudicationCalculator.LineCharge> charges = lines.stream()
                .map(l -> {
                    // Allowed = the fee-schedule amount when the procedure is priced, else the full charge; the
                    // calculator caps it at the charge either way (a plan never allows more than was billed).
                    BigDecimal priced = feeSchedule.get(codeKey(l.getProcedureCodeSystem(), l.getProcedureCode()));
                    BigDecimal allowed = priced != null ? priced : l.getChargeAmount();
                    return new AdjudicationCalculator.LineCharge(l.getLineNumber(), l.getChargeAmount(), allowed);
                })
                .toList();
        return AdjudicationCalculator.adjudicate(
                new AdjudicationCalculator.PlanParameters(
                        plan.getDeductibleAmount(), plan.getCoinsuranceRate(), plan.getCopayAmount()),
                deductibleRemaining, oopRemaining, charges);
    }

    /** The plan's fee-schedule allowed amounts keyed by {@code SYSTEM|CODE} (canonical, matching claim lines). */
    private Map<String, BigDecimal> feeScheduleByCode(UUID organizationId, UUID coveragePlanId) {
        Map<String, BigDecimal> priced = new HashMap<>();
        for (PlanFeeScheduleEntry entry : planFeeSchedule
                .findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(organizationId, coveragePlanId)) {
            priced.put(codeKey(entry.getCodeSystem(), entry.getCode()), entry.getAllowedAmount());
        }
        return priced;
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
