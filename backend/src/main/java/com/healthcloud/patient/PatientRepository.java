package com.healthcloud.patient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-safe by design: every finder is scoped by {@code organizationId}, so a caller can only ever
 * reach patients in their own organization. There is deliberately no {@code findById(id)} used by
 * business code — a bare id lookup would ignore the tenant boundary.
 */
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    /** Load a patient only if it belongs to the given tenant; otherwise empty (→ secure 404). */
    Optional<Patient> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** All patients within one tenant, ordered for stable listing. */
    List<Patient> findByOrganizationIdOrderByFullNameAsc(UUID organizationId);
}
