package com.healthcloud.relationship;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Care-coordinator assignments, tenant-safe by design: every finder is scoped by {@code organizationId}.
 * No bare {@code findById} for business code.
 */
public interface CareCoordinatorAssignmentRepository extends JpaRepository<CareCoordinatorAssignment, UUID> {

    /** Load an assignment only if it belongs to the tenant; otherwise empty (→ secure 404). */
    Optional<CareCoordinatorAssignment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A patient's assignments within the tenant in the given statuses, oldest first. */
    List<CareCoordinatorAssignment> findByOrganizationIdAndPatientIdAndStatusInOrderByAssignedAtAsc(
            UUID organizationId, UUID patientId, Collection<CareCoordinatorAssignmentStatus> statuses);

    /** A coordinator's assignments within the tenant in a given status — used by care-team membership. */
    List<CareCoordinatorAssignment> findByOrganizationIdAndCoordinatorUserIdAndStatus(
            UUID organizationId, UUID coordinatorUserId, CareCoordinatorAssignmentStatus status);
}
