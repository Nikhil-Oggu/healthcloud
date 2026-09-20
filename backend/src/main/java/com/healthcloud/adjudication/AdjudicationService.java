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
import com.healthcloud.coverage.PlanNetworkProviderRepository;
import com.healthcloud.coverage.PlanPriorAuthRequirement;
import com.healthcloud.coverage.PlanPriorAuthRequirementRepository;
import com.healthcloud.audit.AuditAction;
import com.healthcloud.audit.AuditOutcome;
import com.healthcloud.audit.AuditService;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.outbox.OutboxService;
import com.healthcloud.patient.PatientAccessGuard;
import com.healthcloud.priorauth.PriorAuthorizationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final PlanPriorAuthRequirementRepository planPriorAuthRequirements;
    private final PlanNetworkProviderRepository planNetwork;
    private final PriorAuthorizationRepository priorAuths;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;
    private final AuditService audit;
    private final OutboxService outbox;
    private final MeterRegistry meterRegistry;

    public AdjudicationService(ClaimRepository claims, ClaimLineRepository claimLines,
                               ClaimStatusHistoryRepository claimStatusHistory,
                               CoveragePlanRepository coveragePlans, PatientEligibilityRepository eligibility,
                               AdjudicationRepository adjudications, AdjudicationLineRepository adjudicationLines,
                               BenefitAccumulatorRepository accumulators, PlanExclusionRepository planExclusions,
                               PlanFeeScheduleRepository planFeeSchedule,
                               PlanPriorAuthRequirementRepository planPriorAuthRequirements,
                               PlanNetworkProviderRepository planNetwork,
                               PriorAuthorizationRepository priorAuths,
                               PatientAccessGuard accessGuard, UserContextAccessor userContext,
                               AuditService audit, OutboxService outbox, MeterRegistry meterRegistry) {
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
        this.planPriorAuthRequirements = planPriorAuthRequirements;
        this.planNetwork = planNetwork;
        this.priorAuths = priorAuths;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
        this.audit = audit;
        this.outbox = outbox;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Adjudicate a claim. An ACCEPTED claim is adjudicated for the first time (version 1) and advanced to
     * ADJUDICATED; an already-ADJUDICATED claim is <b>re-adjudicated</b>, writing a new immutable version
     * (§Phase 5) while every prior version is retained — its status stays ADJUDICATED. Any other status is a 409.
     * Check order: role → exists + patient gate → adjudicatable state → back out the prior version's benefit
     * contribution (re-adjudication) → find coverage → compute → persist the new adjudication + lines (and, on the
     * first adjudication only, advance the claim + append a status-history row), all in one transaction.
     */
    @Transactional
    public AdjudicationDto adjudicate(UUID claimId) {
        userContext.requireAnyRole(ADJUDICATE_ROLES);
        UserContext caller = userContext.requireUser();
        Claim claim = requireAccessibleClaim(claimId);

        // An ACCEPTED claim is adjudicated (v1); an ADJUDICATED one is re-adjudicated (v+1). Nothing else.
        boolean firstAdjudication = claim.getStatus() == ClaimStatus.ACCEPTED;
        if (!firstAdjudication && claim.getStatus() != ClaimStatus.ADJUDICATED) {
            throw new InvalidStateTransitionException(
                    "Only an ACCEPTED or ADJUDICATED claim can be adjudicated; this claim is "
                            + claim.getStatus() + ".");
        }

        UUID organizationId = claim.getOrganizationId();
        List<ClaimLine> lines = claimLines
                .findByOrganizationIdAndClaimIdOrderByLineNumberAsc(organizationId, claimId);

        // The current (latest) version, if any. Re-adjudication supersedes it by latest-version-wins, and its
        // benefit-accumulator contribution is backed out first so this claim's deductible/OOP is not double-counted.
        Adjudication prior = adjudications
                .findFirstByOrganizationIdAndClaimIdOrderByAdjudicationVersionDesc(organizationId, claimId)
                .orElse(null);
        int nextVersion = prior == null ? 1 : prior.getAdjudicationVersion() + 1;
        if (prior != null) {
            reversePriorContribution(prior, organizationId, claim);
        }

        // Find the coverage in effect on the service date (0 or 1 row — periods are non-overlapping).
        List<PatientEligibility> covering =
                eligibility.findCovering(organizationId, claim.getPatientId(), claim.getServiceDate());
        PatientEligibility enrollment = covering.isEmpty() ? null : covering.get(0);

        Adjudication savedHeader;
        List<AdjudicationLine> savedLines;
        if (enrollment == null) {
            savedHeader = adjudications.save(buildDenial(claim, lines, caller, nextVersion));
            savedLines = saveDenialLines(claim, lines, savedHeader);
        } else {
            CoveragePlan plan = coveragePlans
                    .findByIdAndOrganizationId(enrollment.getCoveragePlanId(), organizationId)
                    .orElseThrow(NotFoundException::new);

            // A procedure the plan excludes is NOT_COVERED; a claim rendered by a provider not in the covering
            // plan's network is OUT_OF_NETWORK; a procedure that requires prior authorization with no APPROVED
            // authorization covering the service date is AUTH_REQUIRED (§Phase 6). All three skip the cost-sharing
            // math and do not touch the deductible or out-of-pocket max — only truly covered lines flow through
            // the calculator. Precedence: exclusion > out-of-network > auth requirement.
            //
            // Out-of-network is a claim-level determination (one header rendering provider): the plan must define
            // a network, the claim must name a rendering provider, and that provider must not be in the network.
            // A null rendering provider imposes no penalty (we cannot prove out-of-network); a plan with no
            // network rows imposes none either (opt-in).
            boolean outOfNetwork = claim.getRenderingProviderId() != null
                    && planNetwork.existsByOrganizationIdAndCoveragePlanId(organizationId, plan.getId())
                    && !planNetwork.existsByOrganizationIdAndCoveragePlanIdAndProviderUserId(
                            organizationId, plan.getId(), claim.getRenderingProviderId());

            Set<String> excluded = excludedCodeKeys(organizationId, plan.getId());
            Set<String> requiresPriorAuth = priorAuthRequiredCodeKeys(organizationId, plan.getId());
            List<ClaimLine> coveredLines = new ArrayList<>();
            List<ClaimLine> excludedLines = new ArrayList<>();
            Set<UUID> outOfNetworkLineIds = new HashSet<>();
            Set<UUID> authRequiredLineIds = new HashSet<>();
            for (ClaimLine line : lines) {
                String key = codeKey(line.getProcedureCodeSystem(), line.getProcedureCode());
                if (excluded.contains(key)) {
                    excludedLines.add(line);
                } else if (outOfNetwork) {
                    outOfNetworkLineIds.add(line.getId());
                } else if (requiresPriorAuth.contains(key)
                        && !priorAuths.existsApprovedCovering(organizationId, claim.getPatientId(), plan.getId(),
                                line.getProcedureCodeSystem().name(), line.getProcedureCode(),
                                claim.getServiceDate())) {
                    authRequiredLineIds.add(line.getId());
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

            // Header totals: the covered math, plus the charges the member owes in full where the plan paid 0 —
            // excluded procedures, out-of-network lines, and procedures needing (missing) prior authorization.
            BigDecimal excludedCharge = totalCharge(excludedLines);
            BigDecimal outOfNetworkCharge = money(lines.stream()
                    .filter(l -> outOfNetworkLineIds.contains(l.getId()))
                    .map(ClaimLine::getChargeAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal authRequiredCharge = money(lines.stream()
                    .filter(l -> authRequiredLineIds.contains(l.getId()))
                    .map(ClaimLine::getChargeAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            savedHeader = adjudications.save(new Adjudication(
                    organizationId, claim.getId(), nextVersion, AdjudicationOutcome.ADJUDICATED,
                    plan.getId(), enrollment.getId(), totalCharge(lines),
                    computation.totalAllowed(), computation.totalPlanPaid(),
                    computation.totalMemberResponsibility()
                            .add(excludedCharge).add(outOfNetworkCharge).add(authRequiredCharge),
                    caller.userId(), CorrelationId.current()));
            savedLines = saveAdjudicationLines(
                    claim, lines, excluded, outOfNetworkLineIds, authRequiredLineIds, computation, savedHeader);
        }

        // §31.6: on the first adjudication only, advance the claim and append its status-history row in the same
        // transaction. Re-adjudication leaves the claim ADJUDICATED (no status change) — the new immutable
        // adjudication row (who/when/version/correlationId) is itself the record of the re-adjudication event.
        if (firstAdjudication) {
            ClaimStatus from = claim.getStatus();
            claim.setStatus(ClaimStatus.ADJUDICATED);
            claims.saveAndFlush(claim);
            claimStatusHistory.save(new ClaimStatusHistory(
                    organizationId, claim.getId(), from, ClaimStatus.ADJUDICATED, caller.userId(),
                    "Adjudicated by the adjudication engine (" + savedHeader.getOutcome() + ")",
                    CorrelationId.current()));
        }

        // §31.6 / §Phase 7: record the money decision in this same transaction — the audit row commits with the
        // adjudication (or both roll back). PHI-free detail: the claim number, the version, and the outcome.
        audit.record(AuditAction.CLAIM_ADJUDICATED, AuditService.RESOURCE_CLAIM, claim.getId(),
                AuditOutcome.SUCCESS,
                "Claim " + claim.getClaimNumber() + " adjudicated v" + savedHeader.getAdjudicationVersion()
                        + " (" + savedHeader.getOutcome() + ")");

        // §31.6 / §Phase 8: emit a claim.adjudicated integration event to the transactional outbox in this same
        // transaction — the outbox row commits with the adjudication (or both roll back), and a relay publishes it
        // to Kafka after commit (a later slice). Minimum-necessary, PHI-free payload.
        outbox.record(OutboxService.AGGREGATE_CLAIM, claim.getId(), "claim.adjudicated",
                ClaimAdjudicatedEvent.from(claim, savedHeader));

        // Phase 11 slice 1: count a *committed* adjudication. Registering the increment on the transaction's
        // afterCommit means a rolled-back adjudication is never counted (a Micrometer counter isn't transactional).
        // Tagged by outcome + type (initial vs reprocess); PHI-free — counts and a coded outcome only (rule 5).
        String outcomeTag = savedHeader.getOutcome().name();
        String typeTag = firstAdjudication ? "initial" : "reprocess";
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Named without a ".total" suffix — the Prometheus registry appends "_total" for
                    // counters, rendering this as `healthcloud_adjudications_total`.
                    Counter.builder("healthcloud.adjudications")
                            .description("Claims adjudicated by the engine (committed)")
                            .tag("outcome", outcomeTag)
                            .tag("type", typeTag)
                            .register(meterRegistry)
                            .increment();
                }
            });
        }

        return AdjudicationDto.from(savedHeader, planName(organizationId, savedHeader.getCoveragePlanId()),
                savedLines);
    }

    /**
     * The current (latest-version) adjudication for a claim, tenant + relationship gated (secure 404 if unreachable
     * or none). Earlier versions are retained and read via {@link #listVersions(UUID)}.
     */
    public AdjudicationDto getByClaim(UUID claimId) {
        Claim claim = requireAccessibleClaim(claimId);
        Adjudication adjudication = adjudications
                .findFirstByOrganizationIdAndClaimIdOrderByAdjudicationVersionDesc(
                        claim.getOrganizationId(), claimId)
                .orElseThrow(NotFoundException::new);
        return toDto(claim.getOrganizationId(), adjudication);
    }

    /** Every adjudication version for a claim, newest first (the immutable version history), gated as above. */
    public List<AdjudicationDto> listVersions(UUID claimId) {
        Claim claim = requireAccessibleClaim(claimId);
        return adjudications
                .findByOrganizationIdAndClaimIdOrderByAdjudicationVersionDesc(claim.getOrganizationId(), claimId)
                .stream()
                .map(a -> toDto(claim.getOrganizationId(), a))
                .toList();
    }

    /** Load an adjudication's lines and render the explainable DTO (the plan that applied + the breakdown). */
    private AdjudicationDto toDto(UUID organizationId, Adjudication adjudication) {
        List<AdjudicationLine> lines = adjudicationLines
                .findByOrganizationIdAndAdjudicationIdOrderByLineNumberAsc(organizationId, adjudication.getId());
        return AdjudicationDto.from(adjudication,
                planName(organizationId, adjudication.getCoveragePlanId()), lines);
    }

    // --- covered path --------------------------------------------------------

    /**
     * Persist one adjudication line per claim line, in order: an excluded procedure becomes a NOT_COVERED line
     * and a procedure needing (missing) prior authorization becomes an AUTH_REQUIRED line — both with the member
     * owing the full charge and the plan paying 0; every other line is a COVERED line built from its computed
     * split. The covered computations are keyed by line number (the calculator only saw the covered lines).
     */
    private List<AdjudicationLine> saveAdjudicationLines(Claim claim, List<ClaimLine> lines,
                                                         Set<String> excluded, Set<UUID> outOfNetworkLineIds,
                                                         Set<UUID> authRequiredLineIds,
                                                         AdjudicationCalculator.Computation computation,
                                                         Adjudication header) {
        Map<Integer, AdjudicationCalculator.LineComputation> byLine = new HashMap<>();
        for (AdjudicationCalculator.LineComputation c : computation.lines()) {
            byLine.put(c.lineNumber(), c);
        }

        List<AdjudicationLine> saved = new ArrayList<>();
        for (ClaimLine line : lines) {
            if (excluded.contains(codeKey(line.getProcedureCodeSystem(), line.getProcedureCode()))) {
                saved.add(adjudicationLines.save(deniedLine(claim, header, line, LineOutcome.NOT_COVERED)));
            } else if (outOfNetworkLineIds.contains(line.getId())) {
                saved.add(adjudicationLines.save(deniedLine(claim, header, line, LineOutcome.OUT_OF_NETWORK)));
            } else if (authRequiredLineIds.contains(line.getId())) {
                saved.add(adjudicationLines.save(deniedLine(claim, header, line, LineOutcome.AUTH_REQUIRED)));
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

    /**
     * A non-paid line: the member owes the full charge, the plan pays 0, and no cost-sharing is consumed. Used for
     * an excluded procedure (NOT_COVERED) and one needing (missing) prior authorization (AUTH_REQUIRED).
     */
    private AdjudicationLine deniedLine(Claim claim, Adjudication header, ClaimLine line, LineOutcome outcome) {
        BigDecimal charge = money(line.getChargeAmount());
        return new AdjudicationLine(
                claim.getOrganizationId(), header.getId(), line.getId(), line.getLineNumber(),
                line.getProcedureCodeSystem(), line.getProcedureCode(), outcome,
                charge, money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO),
                money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO), charge);
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

    /** The set of procedure keys the plan requires prior auth for, as {@code SYSTEM|CODE} (matching claim lines). */
    private Set<String> priorAuthRequiredCodeKeys(UUID organizationId, UUID coveragePlanId) {
        Set<String> keys = new HashSet<>();
        for (PlanPriorAuthRequirement requirement : planPriorAuthRequirements
                .findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(organizationId, coveragePlanId)) {
            keys.add(codeKey(requirement.getCodeSystem(), requirement.getCode()));
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

    /**
     * Back out the prior adjudication version's benefit-accumulator contribution (re-adjudication, §Phase 5), so
     * this claim's deductible and out-of-pocket are not counted twice. A prior denied version (no coverage plan)
     * contributed nothing. The contribution is read back from the prior version's own line snapshot — the
     * deductible applied and the covered lines' member responsibility — and subtracted from the prior version's
     * (plan, year) accumulator under a lock. The benefit year is the claim's service-date year (service date is
     * fixed across versions); the plan is the one the prior version used (usually the same as the new version's).
     */
    private void reversePriorContribution(Adjudication prior, UUID organizationId, Claim claim) {
        if (prior.getCoveragePlanId() == null) {
            return; // a denied prior version never touched an accumulator
        }
        List<AdjudicationLine> priorLines = adjudicationLines
                .findByOrganizationIdAndAdjudicationIdOrderByLineNumberAsc(organizationId, prior.getId());
        BigDecimal priorDeductible = BigDecimal.ZERO;
        BigDecimal priorOutOfPocket = BigDecimal.ZERO;
        for (AdjudicationLine line : priorLines) {
            if (line.getOutcome() == LineOutcome.COVERED) {
                priorDeductible = priorDeductible.add(line.getDeductibleAppliedAmount());
                priorOutOfPocket = priorOutOfPocket.add(line.getMemberResponsibility());
            }
        }
        int benefitYear = claim.getServiceDate().getYear();
        BenefitAccumulator accumulator =
                lockAccumulator(organizationId, claim.getPatientId(), prior.getCoveragePlanId(), benefitYear);
        accumulator.subtract(priorDeductible, priorOutOfPocket);
        accumulators.saveAndFlush(accumulator);
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

    private Adjudication buildDenial(Claim claim, List<ClaimLine> lines, UserContext caller, int version) {
        BigDecimal totalCharge = totalCharge(lines);
        // No plan pays: the member is responsible for the full charge; allowed is 0 (nothing was covered).
        return new Adjudication(
                claim.getOrganizationId(), claim.getId(), version, AdjudicationOutcome.DENIED_NO_ELIGIBILITY,
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
