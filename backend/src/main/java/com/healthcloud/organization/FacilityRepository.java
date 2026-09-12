package com.healthcloud.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityRepository extends JpaRepository<Facility, UUID> {

    List<Facility> findByOrganization_Id(UUID organizationId);

    Optional<Facility> findByOrganization_IdAndNameIgnoreCase(UUID organizationId, String name);
}
