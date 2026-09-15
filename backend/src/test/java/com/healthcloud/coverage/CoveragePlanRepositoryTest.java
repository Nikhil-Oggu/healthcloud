package com.healthcloud.coverage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-key isolation for the coverage-plan repository (Phase 4 slice 5): the {@code (id, organizationId)}
 * lookup is tenant-scoped, and the plan code is unique within a tenant (reusable across tenants). Runs without
 * the {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class CoveragePlanRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired CoveragePlanRepository planRepository;

    private CoveragePlan plan(Organization org, String code) {
        return new CoveragePlan(org.getId(), code, "Standard PPO", PlanType.PPO,
                new BigDecimal("1500.00"), new BigDecimal("0.2000"), new BigDecimal("25.00"),
                new BigDecimal("6000.00"));
    }

    @Test
    void lookup_is_tenant_scoped_and_the_code_is_unique_within_a_tenant() {
        Organization north = organizationRepository.save(new Organization("NorthCare (cp-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (cp-test)"));

        CoveragePlan saved = planRepository.saveAndFlush(plan(north, "PPO-1"));

        assertTrue(planRepository.findByIdAndOrganizationId(saved.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the plan");
        assertTrue(planRepository.findByIdAndOrganizationId(saved.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");

        // The same code in the SAME tenant is rejected...
        assertThrows(DataIntegrityViolationException.class,
                () -> planRepository.saveAndFlush(plan(north, "PPO-1")));
    }

    @Test
    void a_plan_code_may_be_reused_in_a_different_tenant() {
        Organization north = organizationRepository.save(new Organization("North (cp-num)"));
        Organization green = organizationRepository.save(new Organization("Green (cp-num)"));

        planRepository.saveAndFlush(plan(north, "SHARED"));
        // Same code, different tenant — allowed (uniqueness is per organization).
        planRepository.saveAndFlush(plan(green, "SHARED"));
    }
}
