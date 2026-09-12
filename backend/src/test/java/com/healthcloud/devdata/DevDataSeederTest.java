package com.healthcloud.devdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.identity.OrganizationMembership;
import com.healthcloud.identity.OrganizationMembershipRepository;
import com.healthcloud.organization.FacilityMembershipRepository;
import com.healthcloud.organization.FacilityRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the {@link DevDataSeeder} builds the NorthCare / Green Valley demo scenario correctly
 * under the {@code local} profile, against a real PostgreSQL via Testcontainers.
 * {@code @Transactional} keeps the persistence session open so LAZY associations can be read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
@Transactional
class DevDataSeederTest {

    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired FacilityRepository facilities;
    @Autowired FacilityMembershipRepository facilityMemberships;
    @Autowired AppUserRepository users;

    @Test
    void seeds_both_demo_organizations() {
        assertTrue(organizations.findByName("NorthCare Health").isPresent());
        assertTrue(organizations.findByName("Green Valley Clinic").isPresent());
    }

    @Test
    void each_org_has_five_members_all_scoped_to_that_org() {
        Organization north = organizations.findByName("NorthCare Health").orElseThrow();
        Organization green = organizations.findByName("Green Valley Clinic").orElseThrow();

        List<OrganizationMembership> northMembers = memberships.findByOrganization_Id(north.getId());
        List<OrganizationMembership> greenMembers = memberships.findByOrganization_Id(green.getId());

        assertEquals(5, northMembers.size());
        assertEquals(5, greenMembers.size());

        // Tenant isolation: every NorthCare membership belongs to NorthCare, none to Green Valley.
        assertTrue(northMembers.stream().allMatch(m -> m.getOrganization().getId().equals(north.getId())));
        assertTrue(northMembers.stream().noneMatch(m -> m.getOrganization().getId().equals(green.getId())));
    }

    @Test
    void each_org_has_one_facility() {
        Organization north = organizations.findByName("NorthCare Health").orElseThrow();
        Organization green = organizations.findByName("Green Valley Clinic").orElseThrow();
        assertEquals(1, facilities.findByOrganization_Id(north.getId()).size());
        assertEquals(1, facilities.findByOrganization_Id(green.getId()).size());
    }

    @Test
    void provider_is_linked_to_a_facility() {
        Organization north = organizations.findByName("NorthCare Health").orElseThrow();
        AppUser provider = users.findByEmailIgnoreCase("provider@northcare.example.org").orElseThrow();
        OrganizationMembership membership = memberships
                .findByOrganization_IdAndAppUser_Id(north.getId(), provider.getId())
                .orElseThrow();
        assertEquals(1, facilityMemberships.findByMembership_Id(membership.getId()).size());
    }
}
