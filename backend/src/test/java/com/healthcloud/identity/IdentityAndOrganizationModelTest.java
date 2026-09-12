package com.healthcloud.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for the identity & organization data model (Phase 1 slice 2).
 * Runs against a real PostgreSQL 17 via Testcontainers, with Flyway applied, exercising the
 * repositories. This establishes the project's data-access testing pattern: real database, no mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class IdentityAndOrganizationModelTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationMembershipRepository membershipRepository;
    @Autowired UserRoleRepository userRoleRepository;

    @Test
    void the_seven_roles_are_seeded_by_flyway() {
        assertEquals(7, roleRepository.count());
        assertTrue(roleRepository.findByCode("PROVIDER").isPresent());
        assertTrue(roleRepository.findByCode("PLATFORM_ADMIN").isPresent());
    }

    @Test
    void can_persist_org_user_membership_and_query_scoped_to_one_organization() {
        Organization north = organizationRepository.save(new Organization("NorthCare"));
        Organization green = organizationRepository.save(new Organization("Green Valley"));

        AppUser alice = appUserRepository.save(new AppUser("alice@example.org", "Alice Provider"));
        AppUser bob = appUserRepository.save(new AppUser("bob@example.org", "Bob Coordinator"));

        OrganizationMembership aliceAtNorth = membershipRepository.save(new OrganizationMembership(north, alice));
        membershipRepository.save(new OrganizationMembership(green, bob));

        Role provider = roleRepository.findByCode("PROVIDER").orElseThrow();
        userRoleRepository.save(new UserRole(aliceAtNorth, provider));

        // Tenant-scoped query returns ONLY NorthCare's membership (foundation for tenant isolation).
        List<OrganizationMembership> northMembers = membershipRepository.findByOrganization_Id(north.getId());
        assertEquals(1, northMembers.size());
        assertEquals(alice.getId(), northMembers.get(0).getAppUser().getId());

        assertEquals(1, userRoleRepository.findByMembership_Id(aliceAtNorth.getId()).size());
    }

    @Test
    void duplicate_email_is_rejected_case_insensitively() {
        appUserRepository.saveAndFlush(new AppUser("dupe@example.org", "First"));
        assertThrows(DataIntegrityViolationException.class,
                () -> appUserRepository.saveAndFlush(new AppUser("DUPE@example.org", "Second")));
    }

    @Test
    void a_user_cannot_have_two_active_memberships() {
        Organization o1 = organizationRepository.saveAndFlush(new Organization("Org One"));
        Organization o2 = organizationRepository.saveAndFlush(new Organization("Org Two"));
        AppUser user = appUserRepository.saveAndFlush(new AppUser("multi@example.org", "Multi Org"));

        membershipRepository.saveAndFlush(new OrganizationMembership(o1, user)); // ACTIVE
        assertThrows(DataIntegrityViolationException.class,
                () -> membershipRepository.saveAndFlush(new OrganizationMembership(o2, user)));
    }
}
