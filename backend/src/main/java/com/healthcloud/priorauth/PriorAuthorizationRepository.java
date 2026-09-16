package com.healthcloud.priorauth;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Whether an APPROVED prior authorization for this patient/plan/procedure covers the given service date — the
     * hook the adjudication engine calls to gate a covered line whose procedure requires prior auth (§Phase 6).
     * "Covers" means the service date falls within the authorization's window ({@code requestedServiceTo} null =
     * open-ended). The procedure code system is matched by its string name (the entity stores it as text).
     */
    @Query("""
            SELECT COUNT(pa) > 0 FROM PriorAuthorization pa
            WHERE pa.organizationId = :organizationId
              AND pa.patientId = :patientId
              AND pa.coveragePlanId = :coveragePlanId
              AND pa.procedureCodeSystem = :codeSystem
              AND pa.procedureCode = :code
              AND pa.status = com.healthcloud.priorauth.PriorAuthorizationStatus.APPROVED
              AND pa.requestedServiceFrom <= :serviceDate
              AND (pa.requestedServiceTo IS NULL OR pa.requestedServiceTo >= :serviceDate)
            """)
    boolean existsApprovedCovering(@Param("organizationId") UUID organizationId,
                                  @Param("patientId") UUID patientId,
                                  @Param("coveragePlanId") UUID coveragePlanId,
                                  @Param("codeSystem") String codeSystem,
                                  @Param("code") String code,
                                  @Param("serviceDate") LocalDate serviceDate);
}
