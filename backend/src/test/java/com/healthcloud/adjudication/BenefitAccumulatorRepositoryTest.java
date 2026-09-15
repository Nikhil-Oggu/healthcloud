package com.healthcloud.adjudication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.coverage.CoveragePlan;
import com.healthcloud.coverage.CoveragePlanRepository;
import com.healthcloud.coverage.PlanType;
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
 * The benefit accumulator repository (Phase 5 slice 2): insert-if-absent is idempotent, the locked read returns
 * the row, lookups are tenant-scoped, and the unique key is per patient/plan/year. Runs without the
 * {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class BenefitAccumulatorRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired CoveragePlanRepository planRepository;
    @Autowired BenefitAccumulatorRepository accumulatorRepository;

    @Test
    void insert_if_absent_is_idempotent_and_the_row_is_readable() {
        Organization org = organizationRepository.save(new Organization("Accum Org (acc-test)"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8001", "Patient", LocalDate.of(1990, 1, 1)));
        CoveragePlan plan = planRepository.save(plan(org, "PPO-A1"));

        int first = accumulatorRepository.insertIfAbsent(org.getId(), patient.getId(), plan.getId(), 2026);
        int second = accumulatorRepository.insertIfAbsent(org.getId(), patient.getId(), plan.getId(), 2026);
        assertEquals(1, first, "the first call inserts the row");
        assertEquals(0, second, "the second call is a no-op (ON CONFLICT DO NOTHING)");

        BenefitAccumulator locked = accumulatorRepository
                .lockByKey(org.getId(), patient.getId(), plan.getId(), 2026).orElseThrow();
        assertEquals(0, locked.getDeductibleMet().signum(), "a fresh accumulator starts at zero");
        assertEquals(0, locked.getOutOfPocketMet().signum());
    }

    @Test
    void lookups_are_tenant_scoped_and_keyed_by_year() {
        Organization north = organizationRepository.save(new Organization("NorthCare (acc-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (acc-test)"));
        Patient patient = patientRepository.save(
                new Patient(north.getId(), "NC-8101", "Patient", LocalDate.of(1990, 1, 1)));
        CoveragePlan plan = planRepository.save(plan(north, "PPO-A2"));

        accumulatorRepository.insertIfAbsent(north.getId(), patient.getId(), plan.getId(), 2026);

        assertTrue(accumulatorRepository
                .findByOrganizationIdAndPatientIdAndCoveragePlanIdAndBenefitYear(
                        north.getId(), patient.getId(), plan.getId(), 2026).isPresent(),
                "same-tenant, same-year lookup finds the row");
        assertTrue(accumulatorRepository
                .findByOrganizationIdAndPatientIdAndCoveragePlanIdAndBenefitYear(
                        green.getId(), patient.getId(), plan.getId(), 2026).isEmpty(),
                "cross-tenant lookup returns nothing");
        assertTrue(accumulatorRepository
                .findByOrganizationIdAndPatientIdAndCoveragePlanIdAndBenefitYear(
                        north.getId(), patient.getId(), plan.getId(), 2025).isEmpty(),
                "a different benefit year is a separate accumulator");
    }

    @Test
    void add_accrues_deductible_and_out_of_pocket() {
        Organization org = organizationRepository.save(new Organization("Accrue Org (acc-test)"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8201", "Patient", LocalDate.of(1990, 1, 1)));
        CoveragePlan plan = planRepository.save(plan(org, "PPO-A3"));
        accumulatorRepository.insertIfAbsent(org.getId(), patient.getId(), plan.getId(), 2026);

        BenefitAccumulator acc = accumulatorRepository
                .lockByKey(org.getId(), patient.getId(), plan.getId(), 2026).orElseThrow();
        acc.add(new BigDecimal("150.00"), new BigDecimal("150.00"));
        acc.add(new BigDecimal("100.00"), new BigDecimal("120.00"));
        accumulatorRepository.saveAndFlush(acc);

        BenefitAccumulator reread = accumulatorRepository
                .findByOrganizationIdAndPatientIdAndCoveragePlanIdAndBenefitYear(
                        org.getId(), patient.getId(), plan.getId(), 2026).orElseThrow();
        assertEquals(0, new BigDecimal("250.00").compareTo(reread.getDeductibleMet()));
        assertEquals(0, new BigDecimal("270.00").compareTo(reread.getOutOfPocketMet()));
    }

    private CoveragePlan plan(Organization org, String code) {
        return new CoveragePlan(org.getId(), code, "Standard PPO", PlanType.PPO,
                new BigDecimal("1500.00"), new BigDecimal("0.2000"), new BigDecimal("25.00"),
                new BigDecimal("6000.00"));
    }
}
