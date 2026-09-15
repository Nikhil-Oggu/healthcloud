package com.healthcloud.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant scoping and the per-plan/code key for the plan-exclusion repository (Phase 5 slice 4): the
 * {@code (id, organizationId)} lookup is tenant-scoped, the list is by plan, and the existence check is keyed by
 * plan + code. Runs without the {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class PlanExclusionRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired CoveragePlanRepository planRepository;
    @Autowired MedicalCodeRepository medicalCodeRepository;
    @Autowired PlanExclusionRepository exclusionRepository;

    @Test
    void lookup_is_tenant_scoped_and_listed_by_plan() {
        Organization north = organizationRepository.save(new Organization("NorthCare (excl-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (excl-test)"));
        AppUser admin = appUserRepository.save(new AppUser("excl-" + UUID.randomUUID() + "@ex.org", "Admin"));
        CoveragePlan plan = planRepository.save(plan(north, "PPO-X1"));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.CPT, "11111", "Excludable procedure"));

        PlanExclusion saved = exclusionRepository.save(
                new PlanExclusion(north.getId(), plan.getId(), CodeSystem.CPT, "11111", admin.getId()));

        assertTrue(exclusionRepository.findByIdAndOrganizationId(saved.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the row");
        assertTrue(exclusionRepository.findByIdAndOrganizationId(saved.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");

        List<PlanExclusion> forPlan = exclusionRepository
                .findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(north.getId(), plan.getId());
        assertEquals(1, forPlan.size());
        assertEquals("11111", forPlan.get(0).getCode());
    }

    @Test
    void existence_is_keyed_by_plan_and_code() {
        Organization org = organizationRepository.save(new Organization("Excl Key Org (excl-test)"));
        AppUser admin = appUserRepository.save(new AppUser("exk-" + UUID.randomUUID() + "@ex.org", "Admin"));
        CoveragePlan plan = planRepository.save(plan(org, "PPO-X2"));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.CPT, "22222", "Excludable procedure"));
        exclusionRepository.save(new PlanExclusion(org.getId(), plan.getId(), CodeSystem.CPT, "22222", admin.getId()));

        assertTrue(exclusionRepository.existsByOrganizationIdAndCoveragePlanIdAndCodeSystemAndCode(
                org.getId(), plan.getId(), CodeSystem.CPT, "22222"));
        assertFalse(exclusionRepository.existsByOrganizationIdAndCoveragePlanIdAndCodeSystemAndCode(
                org.getId(), plan.getId(), CodeSystem.CPT, "99999"), "a code not excluded is absent");
    }

    private CoveragePlan plan(Organization org, String code) {
        return new CoveragePlan(org.getId(), code, "Standard PPO", PlanType.PPO,
                new BigDecimal("1500.00"), new BigDecimal("0.2000"), new BigDecimal("25.00"),
                new BigDecimal("6000.00"));
    }
}
