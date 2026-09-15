package com.healthcloud.coverage;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Patient eligibility, tenant-owned and always about a patient. Scoped by {@code organizationId} (§32.10) — no
 * bare {@code findById} in business code — so another tenant's row is not found (a secure 404). Reads are
 * additionally narrowed by {@link com.healthcloud.patient.PatientAccessGuard} at the service layer.
 */
public interface PatientEligibilityRepository extends JpaRepository<PatientEligibility, UUID> {

    /** One eligibility record within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<PatientEligibility> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A patient's eligibility records within the tenant, newest coverage first. */
    List<PatientEligibility> findByOrganizationIdAndPatientIdOrderByEffectiveFromDesc(
            UUID organizationId, UUID patientId);

    /**
     * The eligibility periods covering {@code date} for a patient — {@code effectiveFrom ≤ date} and
     * {@code effectiveTo} null or {@code ≥ date}. With non-overlapping periods (enforced in the service) this
     * returns 0 or 1 row; it is the hook the Phase-5 adjudication engine calls for a claim's service date.
     */
    @Query("""
            SELECT e FROM PatientEligibility e
            WHERE e.organizationId = :organizationId
              AND e.patientId = :patientId
              AND e.effectiveFrom <= :date
              AND (e.effectiveTo IS NULL OR e.effectiveTo >= :date)
            """)
    List<PatientEligibility> findCovering(@Param("organizationId") UUID organizationId,
                                          @Param("patientId") UUID patientId,
                                          @Param("date") LocalDate date);
}
