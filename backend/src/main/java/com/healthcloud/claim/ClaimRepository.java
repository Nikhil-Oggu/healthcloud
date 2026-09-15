package com.healthcloud.claim;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Claim headers, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — there is no bare {@code findById} in business code — so another tenant's row is
 * simply not found (a secure 404). Reads are additionally narrowed by {@link
 * com.healthcloud.patient.PatientAccessGuard} at the service layer.
 */
public interface ClaimRepository extends JpaRepository<Claim, UUID> {

    /** One claim within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<Claim> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Whether a claim number is already taken within the tenant (for a clean number allocation). */
    boolean existsByOrganizationIdAndClaimNumber(UUID organizationId, String claimNumber);

    /** All claims in the tenant, newest first (broad-role list). */
    List<Claim> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    /** Claims for one patient in the tenant, newest first. */
    List<Claim> findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(UUID organizationId, UUID patientId);

    /** Claims for a set of patients in the tenant (the gated-caller list scoping), newest first. */
    List<Claim> findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc(UUID organizationId, Set<UUID> patientIds);
}
