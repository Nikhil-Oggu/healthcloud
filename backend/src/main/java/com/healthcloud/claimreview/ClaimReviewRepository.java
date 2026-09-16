package com.healthcloud.claimreview;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /** All reviews in the tenant, newest first (broad-role work queue). */
    List<ClaimReview> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    /** Reviews for one patient in the tenant, newest first. */
    List<ClaimReview> findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(UUID organizationId, UUID patientId);

    /** Reviews for a set of patients in the tenant (the gated-caller list scoping), newest first. */
    List<ClaimReview> findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc(
            UUID organizationId, Set<UUID> patientIds);

    /** Reviews for one claim in the tenant, newest first (the ?claimId= filter). */
    List<ClaimReview> findByOrganizationIdAndClaimIdOrderByCreatedAtDesc(UUID organizationId, UUID claimId);
}
