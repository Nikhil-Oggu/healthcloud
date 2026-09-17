package com.healthcloud.claimreview;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Claim reviews, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's row is simply not
 * found (a secure 404). Reads are additionally narrowed by {@link com.healthcloud.patient.PatientAccessGuard}
 * at the service layer.
 */
public interface ClaimReviewRepository extends JpaRepository<ClaimReview, UUID> {

    /** One review within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<ClaimReview> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Whether a review number is already taken within the tenant (for a clean number allocation). */
    boolean existsByOrganizationIdAndReviewNumber(UUID organizationId, String reviewNumber);

    /** Whether a claim already has a review in the given status (guards against a second open review). */
    boolean existsByOrganizationIdAndClaimIdAndStatus(UUID organizationId, UUID claimId, ClaimReviewStatus status);

    /**
     * A page of the tenant's reviews for a broad-role caller (§Phase 9), optionally filtered to one status and/or a
     * free-text search term. Both filters run in SQL ({@code null} status = any status; {@code null} {@code q} = no
     * search — else a case-insensitive "contains" match on the review number, a PHI-free identifier);
     * ordering/paging come from the {@link Pageable}.
     */
    @Query("select r from ClaimReview r where r.organizationId = :org "
            + "and (:status is null or r.status = :status) "
            + "and (:q is null or lower(r.reviewNumber) like lower(cast(:q as string)) escape '\\')")
    Page<ClaimReview> searchAll(UUID org, ClaimReviewStatus status, String q, Pageable pageable);

    /**
     * A page of the tenant's reviews restricted to a set of patients (the gated-caller scoping — a provider's
     * assigned patients), optionally filtered to one status and/or a free-text review-number search (see
     * {@link #searchAll}). Callers must pass a non-empty {@code patientIds} (an empty accessible set is
     * short-circuited in the service).
     */
    @Query("select r from ClaimReview r where r.organizationId = :org "
            + "and r.patientId in :patientIds "
            + "and (:status is null or r.status = :status) "
            + "and (:q is null or lower(r.reviewNumber) like lower(cast(:q as string)) escape '\\')")
    Page<ClaimReview> searchForPatients(
            UUID org, Collection<UUID> patientIds, ClaimReviewStatus status, String q, Pageable pageable);

    /**
     * A page of the tenant's reviews for one claim (the {@code ?claimId=} filter), optionally filtered to one
     * status and/or a free-text review-number search (see {@link #searchAll}). The caller has already gated the
     * claim by its patient in the service.
     */
    @Query("select r from ClaimReview r where r.organizationId = :org "
            + "and r.claimId = :claimId "
            + "and (:status is null or r.status = :status) "
            + "and (:q is null or lower(r.reviewNumber) like lower(cast(:q as string)) escape '\\')")
    Page<ClaimReview> searchForClaim(UUID org, UUID claimId, ClaimReviewStatus status, String q, Pageable pageable);
}
