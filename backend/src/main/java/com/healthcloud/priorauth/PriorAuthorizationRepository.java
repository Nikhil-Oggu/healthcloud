package com.healthcloud.priorauth;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * A page of the tenant's prior authorizations for a broad-role caller (§Phase 9), optionally filtered to one
     * status. The status is filtered in SQL ({@code null} = any status); ordering/paging come from the
     * {@link Pageable}. Mirrors {@code ClaimRepository.searchAll}.
     */
    @Query("select pa from PriorAuthorization pa where pa.organizationId = :org "
            + "and (:status is null or pa.status = :status)")
    Page<PriorAuthorization> searchAll(UUID org, PriorAuthorizationStatus status, Pageable pageable);

    /**
     * A page of the tenant's prior authorizations restricted to a set of patients (the gated-caller scoping — a
     * provider's assigned patients, or a single {@code ?patientId=}), optionally filtered to one status. Callers
     * must pass a non-empty {@code patientIds} (an empty accessible set is short-circuited in the service).
     */
    @Query("select pa from PriorAuthorization pa where pa.organizationId = :org "
            + "and pa.patientId in :patientIds "
            + "and (:status is null or pa.status = :status)")
    Page<PriorAuthorization> searchForPatients(
            UUID org, Collection<UUID> patientIds, PriorAuthorizationStatus status, Pageable pageable);

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
