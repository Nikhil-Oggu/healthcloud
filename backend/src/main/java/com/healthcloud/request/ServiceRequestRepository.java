package com.healthcloud.request;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-safe by design: every finder is scoped by {@code organizationId}. */
public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, UUID> {

    Optional<ServiceRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ServiceRequest> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<ServiceRequest> findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(UUID organizationId, UUID patientId);
}
