package com.healthcloud.clinical;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Clinical summaries, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — there is no bare {@code findById} in business code — so another tenant's row is
 * simply not found (a secure 404). Reads are additionally narrowed by {@link
 * com.healthcloud.patient.PatientAccessGuard} at the service layer.
 */
public interface ClinicalSummaryRepository extends JpaRepository<ClinicalSummary, UUID> {

    /** One summary within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<ClinicalSummary> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A patient's summaries within the tenant, newest encounter first. */
    List<ClinicalSummary> findByOrganizationIdAndPatientIdOrderByEncounterDateDescCreatedAtDesc(
            UUID organizationId, UUID patientId);
}
