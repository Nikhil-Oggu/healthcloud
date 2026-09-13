package com.healthcloud.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 1 acceptance proof — <b>cross-tenant isolation at the tenant-key layer</b> (§60).
 *
 * <p>Every Phase-2 endpoint will load a tenant-owned row by (organizationId, id), where the
 * organizationId comes from the backend-derived {@code UserContextAccessor.requireOrganizationId()}
 * — never from the client. This proves that lookup mechanism denies cross-tenant access: a row that
 * belongs to one organization is invisible when queried under a different organization's id, and a
 * tenant-scoped listing returns only the caller-org's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class TenantIsolationRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired OrganizationMembershipRepository membershipRepository;

    @Test
    void a_tenant_scoped_lookup_denies_access_to_another_tenants_row() {
        Organization north = organizationRepository.save(new Organization("NorthCare (iso-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (iso-test)"));

        AppUser northUser = appUserRepository.save(new AppUser("iso-north@example.org", "North User"));
        membershipRepository.save(new OrganizationMembership(north, northUser));

        // The real denial pattern: "load this user, but scoped to MY org." Under NorthCare's id the
        // row is found; under Green Valley's id the SAME user id is invisible — no cross-tenant read.
        assertTrue(
                membershipRepository
                        .findByOrganization_IdAndAppUser_Id(north.getId(), northUser.getId())
                        .isPresent(),
                "same-tenant lookup should find the row");
        assertTrue(
                membershipRepository
                        .findByOrganization_IdAndAppUser_Id(green.getId(), northUser.getId())
                        .isEmpty(),
                "cross-tenant lookup (other org + this user) must return nothing");

        // A tenant-scoped listing under the other org never leaks the NorthCare membership.
        assertEquals(0, membershipRepository.findByOrganization_Id(green.getId()).size());
        assertEquals(1, membershipRepository.findByOrganization_Id(north.getId()).size());
    }
}
