package com.healthcloud.priorauth;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Prior authorizations, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's row is simply not
 * found (a secure 404). Reads are additionally narrowed by {@link com.healthcloud.patient.PatientAccessGuard}
 * at the service layer.
 */
public interface PriorAuthorizationRepository extends JpaRepository<PriorAuthorization, UUID> {

    /** One prior authorization within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<PriorAuthorization> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Whether an auth number is already taken within the tenant (for a clean number allocation). */
    boolean existsByOrganizationIdAndAuthNumber(UUID organizationId, String authNumber);

    /** All prior authorizations in the tenant, newest first (broad-role work queue). */
    List<PriorAuthorization> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    /** Prior authorizations for one patient in the tenant, newest first. */
    List<PriorAuthorization> findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(
            UUID organizationId, UUID patientId);

    /** Prior authorizations for a set of patients in the tenant (the gated-caller list scoping), newest first. */
    List<PriorAuthorization> findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc(
            UUID organizationId, Set<UUID> patientIds);
}
