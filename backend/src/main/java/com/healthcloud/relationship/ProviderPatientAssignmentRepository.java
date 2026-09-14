package com.healthcloud.relationship;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Provider-patient assignments, tenant-safe by design: every finder is scoped by {@code organizationId}.
 * No bare {@code findById} for business code.
 */
public interface ProviderPatientAssignmentRepository extends JpaRepository<ProviderPatientAssignment, UUID> {

    /** Load an assignment only if it belongs to the tenant; otherwise empty (→ secure 404). */
    Optional<ProviderPatientAssignment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A patient's assignments within the tenant in the given statuses, oldest first. */
    List<ProviderPatientAssignment> findByOrganizationIdAndPatientIdAndStatusInOrderByAssignedAtAsc(
            UUID organizationId, UUID patientId, Collection<ProviderPatientAssignmentStatus> statuses);
}
