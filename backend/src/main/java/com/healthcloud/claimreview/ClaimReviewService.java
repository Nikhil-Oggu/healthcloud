package com.healthcloud.claimreview;

import com.healthcloud.claim.Claim;
import com.healthcloud.claim.ClaimRepository;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientAccessGuard;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual-review reads, intake and the resolution lifecycle (source-of-truth §Phase 6, advanced claims). Always
 * scoped to the caller's tenant (org derived from the loaded claim/review, never the client). Every operation
 * passes the layered authorization pipeline (§21): tenant → function/role → object/relationship
 * ({@link PatientAccessGuard}, via the review's patient — a review is about a claim, so open gates through the
 * claim's patient). A review carries only claims-domain data (why opened + the conclusion, no clinical narrative),
 * so there is no consent field masking here.
 *
 * <p>Intake and each transition follow the one-transaction aggregate pattern (§31.6): the domain change and its
 * status-history row are written atomically. The state machine lives in the pure {@link ClaimReviewTransitions}
 * policy; this service loads data and applies it. Mirrors {@code AppealService}: opened against a parent claim,
 * gated by that claim's patient, with a partial-unique "one open review per claim" guard.
 *
 * <p>A review is a <b>tracking</b> record: opening one neither holds the claim nor changes its status. The
 * reviewer still uses the existing claim accept/reject/adjudicate actions.
 */
@Service
@Transactional(readOnly = true)
public class ClaimReviewService {

    /** Roles allowed to open a review (a coordinator or reviewer flags a claim for a human look). */
    private static final String[] OPEN_ROLES = {"CARE_COORDINATOR", "CLAIMS_REVIEWER", "ORG_ADMIN"};

    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final ClaimReviewRepository reviews;
    private final ClaimReviewStatusHistoryRepository statusHistory;
    private final ClaimRepository claims;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public ClaimReviewService(ClaimReviewRepository reviews, ClaimReviewStatusHistoryRepository statusHistory,
                              ClaimRepository claims, PatientAccessGuard accessGuard,
                              UserContextAccessor userContext) {
        this.reviews = reviews;
        this.statusHistory = statusHistory;
        this.claims = claims;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * Open a manual review (OPEN) on a claim the caller can reach. Requires an open role (403 otherwise) AND —
     * for a PROVIDER, though providers cannot open — an active assignment to the claim's patient (the guard, else
     * secure 404). The claim must not already have an open review (→ 409). The patient is taken from the claim
     * (never the client). The review + its null → OPEN history row are written in one transaction.
     */
    @Transactional
    public ClaimReviewDto open(CreateClaimReviewRequest request) {
        userContext.requireAnyRole(OPEN_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        // Load the claim in-tenant, then gate by its patient (§21 layer 6): an unreachable claim is a secure 404.
        Claim claim = claims.findByIdAndOrganizationId(request.claimId(), organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(claim.getPatientId());

        if (reviews.existsByOrganizationIdAndClaimIdAndStatus(
                organizationId, claim.getId(), ClaimReviewStatus.OPEN)) {
            throw new ConflictException("This claim already has an open review.");
        }

        ClaimReview saved = reviews.save(new ClaimReview(
                organizationId,
                claim.getId(),
                claim.getPatientId(),
                allocateReviewNumber(organizationId),
                request.reason() == null ? null : request.reason().strip(),
                caller.userId()));

        // §31.6: domain change + status history in one transaction. from=null marks creation.
        statusHistory.save(new ClaimReviewStatusHistory(
                organizationId, saved.getId(), null, ClaimReviewStatus.OPEN,
                caller.userId(), "Review opened", CorrelationId.current()));

        return ClaimReviewDto.from(saved);
    }

    /**
     * Apply a controlled review transition (resolve/cancel). In one transaction: gate by tenant + patient, then
     * validate — order of checks: exists → legal move → role → reason → optimistic version — then update the
     * status (stamping the resolver on a resolve) and append a history row.
     */
    @Transactional
    public ClaimReviewDto changeStatus(UUID reviewId, ClaimReviewStatusChangeRequest change) {
        UserContext caller = userContext.requireUser();
        // Tenant + object/relationship gate: an unreachable review is a secure 404 before any state leaks.
        ClaimReview review = requireAccessibleReview(reviewId);

        ClaimReviewStatus from = review.getStatus();
        ClaimReviewStatus to = change.targetStatus();

        // 1. Is this a legal move at all?
        if (!ClaimReviewTransitions.isAllowed(from, to)) {
            throw new InvalidStateTransitionException(
                    "Cannot change review status from " + from + " to " + to + ".");
        }
        // 2. May this caller perform it? (§12.1 function permission)
        if (!ClaimReviewTransitions.isRoleAllowed(from, to, caller.roles())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
        }
        // 3. A reason is required on every review transition.
        if (ClaimReviewTransitions.reasonRequired(to) && (change.reason() == null || change.reason().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A reason is required to " + to + " this review.");
        }
        // 4. Optimistic locking: reject a stale caller (someone else moved it first).
        if (review.getVersion() != change.expectedVersion()) {
            throw new ConflictException("This review was modified by someone else; reload and try again.");
        }

        // A resolve stamps who/when + the conclusion; a cancellation is a plain status change.
        if (ClaimReviewTransitions.isDecision(to)) {
            review.resolve(caller.userId(), change.reason());
        } else {
            review.setStatus(to);
        }
        ClaimReview saved = reviews.saveAndFlush(review); // bump @Version; response carries the new one

        statusHistory.save(new ClaimReviewStatusHistory(
                review.getOrganizationId(), review.getId(), from, to, caller.userId(),
                change.reason(), CorrelationId.current()));

        return ClaimReviewDto.from(saved);
    }

    /** One review in the caller's tenant, gated by its patient (§21 layer 6), or a secure 404. */
    public ClaimReviewDto getById(UUID reviewId) {
        return ClaimReviewDto.from(requireAccessibleReview(reviewId));
    }

    /** The review's status timeline (append-only history), tenant + relationship gated (secure 404). */
    public List<ClaimReviewStatusHistoryDto> getHistory(UUID reviewId) {
        ClaimReview review = requireAccessibleReview(reviewId);
        return statusHistory
                .findByOrganizationIdAndClaimReviewIdOrderByCreatedAtAsc(review.getOrganizationId(), reviewId)
                .stream()
                .map(ClaimReviewStatusHistoryDto::from)
                .toList();
    }

    /**
     * Reviews in the caller's tenant (header-only), optionally filtered to one claim and/or a status. Gated by
     * patient (§21 layer 6): a provider sees only reviews for patients they are actively assigned to; broad roles
     * (coordinator/admin/reviewer) see the tenant's reviews — the reviewer's work queue.
     */
    public List<ClaimReviewSummaryDto> list(Optional<UUID> claimId, Optional<ClaimReviewStatus> status) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        List<ClaimReview> found;
        if (claimId.isPresent()) {
            // Reuse the patient gate via the claim: an inaccessible claim (another tenant, or unassigned
            // provider) → 404, so a filtered listing cannot confirm a claim the caller cannot reach.
            Claim claim = claims.findByIdAndOrganizationId(claimId.get(), organizationId)
                    .orElseThrow(NotFoundException::new);
            accessGuard.requireAccessibleInTenant(claim.getPatientId());
            found = reviews.findByOrganizationIdAndClaimIdOrderByCreatedAtDesc(organizationId, claimId.get());
        } else {
            Optional<Set<UUID>> accessibleIds = accessGuard.accessiblePatientIdsIfGated(caller, organizationId);
            if (accessibleIds.isPresent()) {
                Set<UUID> visible = accessibleIds.get();
                found = visible.isEmpty()
                        ? List.of()
                        : reviews.findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc(organizationId, visible);
            } else {
                found = reviews.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
            }
        }
        return found.stream()
                .filter(r -> status.isEmpty() || r.getStatus() == status.get())
                .map(ClaimReviewSummaryDto::from)
                .toList();
    }

    /**
     * Load a review in the caller's tenant and confirm the caller may reach its patient (§21 layer 6), else a
     * secure 404. The single choke point for every read that names one review.
     */
    private ClaimReview requireAccessibleReview(UUID reviewId) {
        UUID organizationId = userContext.requireOrganizationId();
        ClaimReview review = reviews.findByIdAndOrganizationId(reviewId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(review.getPatientId());
        return review;
    }

    /** Allocate a review number unique within the tenant (the unique index is the backstop). */
    private String allocateReviewNumber(UUID organizationId) {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "MRV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!reviews.existsByOrganizationIdAndReviewNumber(organizationId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("Could not allocate a unique review number; please retry.");
    }
}
