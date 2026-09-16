package com.healthcloud.reprocessing;

import com.healthcloud.adjudication.Adjudication;
import com.healthcloud.adjudication.AdjudicationDto;
import com.healthcloud.adjudication.AdjudicationRepository;
import com.healthcloud.adjudication.AdjudicationService;
import com.healthcloud.claim.Claim;
import com.healthcloud.claim.ClaimRepository;
import com.healthcloud.claim.ClaimStatus;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.coverage.CoveragePlan;
import com.healthcloud.coverage.CoveragePlanRepository;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch re-adjudication (source-of-truth §Phase 6, advanced claims). After a plan-config change, an admin/reviewer
 * runs a {@link ReprocessingBatch} for a coverage plan: every already-ADJUDICATED claim currently on that plan is
 * re-run through the (unchanged) {@link AdjudicationService} re-adjudication path, each producing a new immutable
 * adjudication version. This service <b>orchestrates only</b> — it changes no adjudication math.
 *
 * <p><b>Transaction shape (a deliberate departure from the one-transaction rule).</b> A batch is many independent
 * transactions, not one big one: {@link #createAndRun} runs {@code NOT_SUPPORTED} (no surrounding transaction), so
 * each {@code adjudication.adjudicate(claimId)} — a separate bean's {@code @Transactional} method — commits or
 * rolls back on its own. One claim's failure is caught and recorded as a FAILED item; it never rolls back the
 * other claims or the batch record. This is appropriate for a batch job (the async, recoverable version is
 * Phase 8).
 *
 * <p><b>Authorization.</b> The batch is gated to CLAIMS_REVIEWER/ORG_ADMIN (the roles the engine command already
 * requires); those are broad roles, so every tenant claim is reachable and the per-claim re-adjudication's own
 * layered gate (§21: tenant → role → {@link com.healthcloud.patient.PatientAccessGuard} via the claim) composes
 * cleanly. Reads are tenant-scoped (another tenant's batch is a secure 404). Not consent field-masked
 * (claims/benefits data).
 */
@Service
@Transactional(readOnly = true)
public class ReprocessingService {

    /** Roles allowed to run a batch — the reviewer's/admin's action (matches the adjudication engine command). */
    private static final String[] REPROCESS_ROLES = {"CLAIMS_REVIEWER", "ORG_ADMIN"};

    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final ReprocessingBatchRepository batches;
    private final ReprocessingItemRepository items;
    private final CoveragePlanRepository coveragePlans;
    private final ClaimRepository claims;
    private final AdjudicationRepository adjudications;
    private final AdjudicationService adjudication;
    private final UserContextAccessor userContext;

    public ReprocessingService(ReprocessingBatchRepository batches, ReprocessingItemRepository items,
                               CoveragePlanRepository coveragePlans, ClaimRepository claims,
                               AdjudicationRepository adjudications, AdjudicationService adjudication,
                               UserContextAccessor userContext) {
        this.batches = batches;
        this.items = items;
        this.coveragePlans = coveragePlans;
        this.claims = claims;
        this.adjudications = adjudications;
        this.adjudication = adjudication;
        this.userContext = userContext;
    }

    /**
     * Create and run a reprocessing batch for a coverage plan. Requires CLAIMS_REVIEWER/ORG_ADMIN (403 otherwise)
     * and an in-tenant plan (else 400). Selects the tenant's ADJUDICATED claims currently on that plan, then
     * re-adjudicates each one in its own transaction, recording a per-claim item. Runs synchronously and returns
     * the finished batch (COMPLETED, or COMPLETED_WITH_ERRORS if any claim failed).
     *
     * <p>{@code NOT_SUPPORTED}: this method holds no transaction, so the per-claim engine calls and the batch/item
     * saves are each their own short transaction (see the class note).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReprocessingBatchDto createAndRun(CreateReprocessingBatchRequest request) {
        userContext.requireAnyRole(REPROCESS_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        CoveragePlan plan = coveragePlans.findByIdAndOrganizationId(request.coveragePlanId(), organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "The coverage plan is not in your organization."));

        List<UUID> targets = selectClaims(organizationId, plan.getId());

        // Persist the RUNNING header first so items can FK to it (each save is its own short transaction).
        ReprocessingBatch batch = batches.save(new ReprocessingBatch(
                organizationId, allocateBatchNumber(organizationId), plan.getId(), caller.userId()));

        List<ReprocessingItem> resultItems = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (UUID claimId : targets) {
            try {
                // A separate bean's @Transactional method → its own transaction. Re-adjudicates (v+1).
                AdjudicationDto result = adjudication.adjudicate(claimId);
                resultItems.add(ReprocessingItem.succeeded(
                        organizationId, batch.getId(), claimId, result.adjudicationVersion()));
                succeeded++;
            } catch (RuntimeException e) {
                // One claim's failure is recorded, not propagated — the batch and the other claims survive.
                resultItems.add(ReprocessingItem.failed(
                        organizationId, batch.getId(), claimId, safeMessage(e)));
                failed++;
            }
        }
        items.saveAll(resultItems);

        batch.finish(targets.size(), succeeded, failed);
        batch = batches.save(batch);

        return ReprocessingBatchDto.from(batch, plan.getName(), resultItems);
    }

    /** One batch by id (header + items), scoped to the caller's tenant (404 across tenants). */
    public ReprocessingBatchDto getById(UUID batchId) {
        UUID organizationId = userContext.requireOrganizationId();
        ReprocessingBatch batch = batches.findByIdAndOrganizationId(batchId, organizationId)
                .orElseThrow(NotFoundException::new);
        List<ReprocessingItem> batchItems = items
                .findByOrganizationIdAndReprocessingBatchIdOrderByCreatedAtAsc(organizationId, batchId);
        return ReprocessingBatchDto.from(batch, planName(organizationId, batch.getCoveragePlanId()), batchItems);
    }

    /** All batches in the caller's tenant, newest first (the work queue), header-only. */
    public List<ReprocessingBatchSummaryDto> list() {
        UUID organizationId = userContext.requireOrganizationId();
        return batches.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .map(b -> ReprocessingBatchSummaryDto.from(b, planName(organizationId, b.getCoveragePlanId())))
                .toList();
    }

    /**
     * The tenant's ADJUDICATED claims whose <i>current</i> adjudication version is on the given plan — the claims
     * a config change to that plan should re-run. A claim can carry versions on different plans over time; we
     * target only those currently on this plan (re-running another plan's claim would be off-scope, though the
     * engine would recompute it harmlessly).
     */
    private List<UUID> selectClaims(UUID organizationId, UUID coveragePlanId) {
        List<UUID> targets = new ArrayList<>();
        for (UUID claimId : adjudications.findDistinctClaimIdsByCoveragePlan(organizationId, coveragePlanId)) {
            Claim claim = claims.findByIdAndOrganizationId(claimId, organizationId).orElse(null);
            if (claim == null || claim.getStatus() != ClaimStatus.ADJUDICATED) {
                continue;
            }
            Adjudication current = adjudications
                    .findFirstByOrganizationIdAndClaimIdOrderByAdjudicationVersionDesc(organizationId, claimId)
                    .orElse(null);
            if (current != null && coveragePlanId.equals(current.getCoveragePlanId())) {
                targets.add(claimId);
            }
        }
        return targets;
    }

    /** A short, PHI-free failure reason: our own controlled messages, or a generic line for the unexpected. */
    private static String safeMessage(RuntimeException e) {
        String message = e instanceof ApiException && e.getMessage() != null
                ? e.getMessage()
                : "Re-adjudication failed unexpectedly.";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String planName(UUID organizationId, UUID coveragePlanId) {
        return coveragePlans.findByIdAndOrganizationId(coveragePlanId, organizationId)
                .map(CoveragePlan::getName)
                .orElse(null);
    }

    /** Allocate a batch number unique within the tenant (the unique index is the backstop). */
    private String allocateBatchNumber(UUID organizationId) {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "RPB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!batches.existsByOrganizationIdAndBatchNumber(organizationId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("Could not allocate a unique batch number; please retry.");
    }
}
