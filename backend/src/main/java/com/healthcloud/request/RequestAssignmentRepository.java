package com.healthcloud.request;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Request assignments, scoped to the tenant + request. */
public interface RequestAssignmentRepository extends JpaRepository<RequestAssignment, UUID> {

    /** The current active assignment for a request in the tenant, if any. */
    Optional<RequestAssignment> findByOrganizationIdAndServiceRequestIdAndStatus(
            UUID organizationId, UUID serviceRequestId, RequestAssignmentStatus status);
}
