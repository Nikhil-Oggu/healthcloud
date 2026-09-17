package com.healthcloud.priorauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.coverage.CoveragePlan;
import com.healthcloud.coverage.CoveragePlanRepository;
import com.healthcloud.coverage.PlanType;
import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-key isolation and structural integrity for the prior-authorization aggregate (Phase 6 slice 1): the
 * {@code (id, organizationId)} lookup is tenant-scoped, the auth number is unique <i>within</i> a tenant
 * (reusable across tenants), history loads in order, and the procedure must be a real catalog code (the FK to
 * the global {@code medical_code}). Runs without the {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class PriorAuthorizationRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired MedicalCodeRepository medicalCodeRepository;
    @Autowired CoveragePlanRepository coveragePlanRepository;
    @Autowired PriorAuthorizationRepository priorAuthRepository;
    @Autowired PriorAuthorizationStatusHistoryRepository historyRepository;

    private CoveragePlan plan(Organization org, String code) {
        return coveragePlanRepository.save(new CoveragePlan(
                org.getId(), code, "Test Plan", PlanType.PPO,
                new BigDecimal("1000.00"), new BigDecimal("0.2000"), new BigDecimal("25.00"),
                new BigDecimal("5000.00")));
    }

    @Test
    void lookup_is_tenant_scoped_and_the_number_is_unique_within_a_tenant() {
        Organization north = organizationRepository.save(new Organization("NorthCare (pa-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (pa-test)"));
        AppUser author = appUserRepository.save(new AppUser("pa-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(north.getId(), "NC-8001", "North Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.CPT, "99213", "Office visit"));
        CoveragePlan ppo = plan(north, "PA-PPO-1");

        PriorAuthorization auth = priorAuthRepository.saveAndFlush(new PriorAuthorization(
                north.getId(), patient.getId(), "PA-1", ppo.getId(), "CPT", "99213",
                LocalDate.now(), null, author.getId()));

        assertTrue(priorAuthRepository.findByIdAndOrganizationId(auth.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the authorization");
        assertTrue(priorAuthRepository.findByIdAndOrganizationId(auth.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");

        // The same auth number in the SAME tenant is rejected.
        assertThrows(DataIntegrityViolationException.class,
                () -> priorAuthRepository.saveAndFlush(new PriorAuthorization(
                        north.getId(), patient.getId(), "PA-1", ppo.getId(), "CPT", "99213",
                        LocalDate.now(), null, author.getId())));
    }

    @Test
    void an_auth_number_may_be_reused_in_a_different_tenant() {
        Organization north = organizationRepository.save(new Organization("North (pa-num)"));
        Organization green = organizationRepository.save(new Organization("Green (pa-num)"));
        AppUser author = appUserRepository.save(new AppUser("panum-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient northPatient = patientRepository.save(
                new Patient(north.getId(), "NC-8101", "North P", LocalDate.of(1990, 1, 1)));
        Patient greenPatient = patientRepository.save(
                new Patient(green.getId(), "GV-8101", "Green P", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.CPT, "99213", "Office visit"));
        CoveragePlan northPlan = plan(north, "PA-PPO-N");
        CoveragePlan greenPlan = plan(green, "PA-PPO-G");

        priorAuthRepository.saveAndFlush(new PriorAuthorization(
                north.getId(), northPatient.getId(), "PA-SHARED", northPlan.getId(), "CPT", "99213",
                LocalDate.now(), null, author.getId()));
        // Same number, different tenant — allowed (uniqueness is per organization).
        priorAuthRepository.saveAndFlush(new PriorAuthorization(
                green.getId(), greenPatient.getId(), "PA-SHARED", greenPlan.getId(), "CPT", "99213",
                LocalDate.now(), null, author.getId()));
    }

    @Test
    void history_loads_oldest_first() {
        Organization org = organizationRepository.save(new Organization("Hist Org (pa-test)"));
        AppUser actor = appUserRepository.save(new AppUser("pah-" + UUID.randomUUID() + "@ex.org", "Actor"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8201", "Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.CPT, "99213", "Office visit"));
        CoveragePlan ppo = plan(org, "PA-PPO-H");
        PriorAuthorization auth = priorAuthRepository.save(new PriorAuthorization(
                org.getId(), patient.getId(), "PA-H", ppo.getId(), "CPT", "99213",
                LocalDate.now(), null, actor.getId()));

        historyRepository.save(new PriorAuthorizationStatusHistory(
                org.getId(), auth.getId(), null, PriorAuthorizationStatus.REQUESTED, actor.getId(), null, null));
        historyRepository.save(new PriorAuthorizationStatusHistory(
                org.getId(), auth.getId(), PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.APPROVED,
                actor.getId(), null, null));

        List<PriorAuthorizationStatusHistory> history = historyRepository
                .findByOrganizationIdAndPriorAuthorizationIdOrderByCreatedAtAsc(org.getId(), auth.getId());
        assertEquals(
                List.of(PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.APPROVED),
                history.stream().map(PriorAuthorizationStatusHistory::getToStatus).toList(),
                "history reads chronologically");
    }

    @Test
    void searchAll_pages_counts_sorts_and_filters_by_status_in_the_database() {
        Organization org = organizationRepository.save(new Organization("PA Search Org"));
        AppUser author = appUserRepository.save(new AppUser("pas-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8401", "Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.CPT, "99213", "Office visit"));
        CoveragePlan ppo = plan(org, "PA-PPO-S");

        // Three REQUESTED with ascending service-from dates, plus one APPROVED.
        savedAuth(org, patient, author, ppo, "PA-S1", LocalDate.of(2026, 1, 1), PriorAuthorizationStatus.REQUESTED);
        savedAuth(org, patient, author, ppo, "PA-S2", LocalDate.of(2026, 2, 1), PriorAuthorizationStatus.REQUESTED);
        savedAuth(org, patient, author, ppo, "PA-S3", LocalDate.of(2026, 3, 1), PriorAuthorizationStatus.REQUESTED);
        savedAuth(org, patient, author, ppo, "PA-S4", LocalDate.of(2026, 4, 1), PriorAuthorizationStatus.APPROVED);

        Page<PriorAuthorization> firstPage = priorAuthRepository.searchAll(
                org.getId(), null, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "requestedServiceFrom")));
        assertEquals(4, firstPage.getTotalElements(), "the count spans every matching row, not just the page");
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(List.of("PA-S4", "PA-S3"),
                firstPage.getContent().stream().map(PriorAuthorization::getAuthNumber).toList(),
                "sorted by service-from date descending, in the database");
        assertTrue(firstPage.isFirst());

        Page<PriorAuthorization> approved = priorAuthRepository.searchAll(
                org.getId(), PriorAuthorizationStatus.APPROVED, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(1, approved.getTotalElements());
        assertEquals("PA-S4", approved.getContent().get(0).getAuthNumber());
    }

    @Test
    void searchForPatients_scopes_to_the_given_patients_and_is_tenant_scoped() {
        Organization org = organizationRepository.save(new Organization("PA Scope Org"));
        AppUser author = appUserRepository.save(new AppUser("pasc-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient p1 = patientRepository.save(new Patient(org.getId(), "NC-8501", "P1", LocalDate.of(1990, 1, 1)));
        Patient p2 = patientRepository.save(new Patient(org.getId(), "NC-8502", "P2", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.CPT, "99213", "Office visit"));
        CoveragePlan ppo = plan(org, "PA-PPO-SC");

        savedAuth(org, p1, author, ppo, "PA-P1A", LocalDate.of(2026, 1, 1), PriorAuthorizationStatus.REQUESTED);
        savedAuth(org, p1, author, ppo, "PA-P1B", LocalDate.of(2026, 2, 1), PriorAuthorizationStatus.REQUESTED);
        savedAuth(org, p2, author, ppo, "PA-P2A", LocalDate.of(2026, 1, 1), PriorAuthorizationStatus.REQUESTED);

        Page<PriorAuthorization> onlyP1 = priorAuthRepository.searchForPatients(
                org.getId(), Set.of(p1.getId()), null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(2, onlyP1.getTotalElements(), "only the requested patient's authorizations are visible");
        assertTrue(onlyP1.getContent().stream().allMatch(a -> a.getPatientId().equals(p1.getId())));

        Organization other = organizationRepository.save(new Organization("PA Other Org"));
        Page<PriorAuthorization> crossTenant = priorAuthRepository.searchForPatients(
                other.getId(), Set.of(p1.getId(), p2.getId()), null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(0, crossTenant.getTotalElements());
    }

    /** Save a prior authorization with an explicit status (default on creation is REQUESTED). */
    private PriorAuthorization savedAuth(Organization org, Patient patient, AppUser author, CoveragePlan plan,
                                         String number, LocalDate serviceFrom, PriorAuthorizationStatus status) {
        PriorAuthorization auth = new PriorAuthorization(
                org.getId(), patient.getId(), number, plan.getId(), "CPT", "99213", serviceFrom, null,
                author.getId());
        auth.setStatus(status);
        return priorAuthRepository.saveAndFlush(auth);
    }

    @Test
    void a_procedure_must_be_a_real_catalog_code() {
        Organization org = organizationRepository.save(new Organization("FK Org (pa-test)"));
        AppUser author = appUserRepository.save(new AppUser("pafk-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8301", "Patient", LocalDate.of(1990, 1, 1)));
        CoveragePlan ppo = plan(org, "PA-PPO-FK");

        // No such code seeded → the FK to medical_code rejects the insert.
        assertThrows(DataIntegrityViolationException.class,
                () -> priorAuthRepository.saveAndFlush(new PriorAuthorization(
                        org.getId(), patient.getId(), "PA-FK", ppo.getId(), "CPT", "00000",
                        LocalDate.now(), null, author.getId())));
    }
}
