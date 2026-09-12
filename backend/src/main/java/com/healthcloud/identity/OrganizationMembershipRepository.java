package com.healthcloud.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    /** All memberships within one organization (tenant-scoped query). */
    List<OrganizationMembership> findByOrganization_Id(UUID organizationId);

    Optional<OrganizationMembership> findByOrganization_IdAndAppUser_Id(UUID organizationId, UUID appUserId);
}
