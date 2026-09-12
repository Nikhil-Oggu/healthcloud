package com.healthcloud.organization;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityMembershipRepository extends JpaRepository<FacilityMembership, UUID> {

    List<FacilityMembership> findByFacility_Id(UUID facilityId);

    List<FacilityMembership> findByMembership_Id(UUID organizationMembershipId);
}
