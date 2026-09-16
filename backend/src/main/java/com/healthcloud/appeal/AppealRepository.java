package com.healthcloud.appeal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Appeals, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's row is simply not
 * found (a secure 404). Reads are additionally narrowed by {@link com.healthcloud.patient.PatientAccessGuard}
 * at the service layer.
 */
public interface AppealRepository extends JpaRepository<Appeal, UUID> {

    /** One appeal within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<Appeal> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Whether an appeal number is already taken within the tenant (for a clean number allocation). */
    boolean existsByOrganizationIdAndAppealNumber(UUID organizationId, String appealNumber);

    /** Whether a claim already has an appeal in the given status (guards against a second open appeal). */
    boolean existsByOrganizationIdAndClaimIdAndStatus(UUID organizationId, UUID claimId, AppealStatus status);

    /** All appeals in the tenant, newest first (broad-role work queue). */
    List<Appeal> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    /** Appeals for one patient in the tenant, newest first. */
    List<Appeal> findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(UUID organizationId, UUID patientId);

    /** Appeals for a set of patients in the tenant (the gated-caller list scoping), newest first. */
    List<Appeal> findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc(UUID organizationId, Set<UUID> patientIds);

    /** Appeals for one claim in the tenant, newest first (the ?claimId= filter). */
    List<Appeal> findByOrganizationIdAndClaimIdOrderByCreatedAtDesc(UUID organizationId, UUID claimId);
}
