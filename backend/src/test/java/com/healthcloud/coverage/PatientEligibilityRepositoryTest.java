package com.healthcloud.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-key isolation and the covering-date query for the patient-eligibility repository (Phase 4 slice 6):
 * the {@code (id, organizationId)} lookup is tenant-scoped, and {@code findCovering} matches only the period
 * whose window contains a date. Runs without the {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class PatientEligibilityRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired CoveragePlanRepository planRepository;
    @Autowired PatientEligibilityRepository eligibilityRepository;

    @Test
    void lookup_is_tenant_scoped() {
        Organization north = organizationRepository.save(new Organization("NorthCare (elig-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (elig-test)"));
        AppUser enroller = appUserRepository.save(new AppUser("elig-" + UUID.randomUUID() + "@ex.org", "Enroller"));
        Patient patient = patientRepository.save(
                new Patient(north.getId(), "NC-6001", "North Patient", LocalDate.of(1990, 1, 1)));
        CoveragePlan plan = planRepository.save(plan(north, "PPO-E1"));

        PatientEligibility saved = eligibilityRepository.save(new PatientEligibility(
                north.getId(), patient.getId(), plan.getId(), "M-1",
                LocalDate.of(2026, 1, 1), null, enroller.getId()));

        assertTrue(eligibilityRepository.findByIdAndOrganizationId(saved.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the row");
        assertTrue(eligibilityRepository.findByIdAndOrganizationId(saved.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");
    }

    @Test
    void find_covering_matches_only_the_period_containing_the_date() {
        Organization org = organizationRepository.save(new Organization("Cover Org (elig-test)"));
        AppUser enroller = appUserRepository.save(new AppUser("cov-" + UUID.randomUUID() + "@ex.org", "Enroller"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-6101", "Patient", LocalDate.of(1990, 1, 1)));
        CoveragePlan plan = planRepository.save(plan(org, "PPO-E2"));

        // A closed 2025 period and an open-ended 2026 period (non-overlapping).
        eligibilityRepository.save(new PatientEligibility(org.getId(), patient.getId(), plan.getId(), "M-A",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), enroller.getId()));
        eligibilityRepository.save(new PatientEligibility(org.getId(), patient.getId(), plan.getId(), "M-B",
                LocalDate.of(2026, 1, 1), null, enroller.getId()));

        assertEquals(1, eligibilityRepository.findCovering(org.getId(), patient.getId(),
                LocalDate.of(2025, 6, 15)).size(), "a mid-2025 date matches the closed period");
        assertEquals(1, eligibilityRepository.findCovering(org.getId(), patient.getId(),
                LocalDate.of(2030, 1, 1)).size(), "a far-future date matches the open-ended period");
        assertTrue(eligibilityRepository.findCovering(org.getId(), patient.getId(),
                LocalDate.of(2024, 1, 1)).isEmpty(), "a date before any period matches nothing");
    }

    private CoveragePlan plan(Organization org, String code) {
        return new CoveragePlan(org.getId(), code, "Standard PPO", PlanType.PPO,
                new BigDecimal("1500.00"), new BigDecimal("0.2000"), new BigDecimal("25.00"),
                new BigDecimal("6000.00"));
    }
}
