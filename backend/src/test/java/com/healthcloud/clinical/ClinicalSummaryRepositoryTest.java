package com.healthcloud.clinical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-key isolation and structural integrity for the clinical-summary repository (Phase 4 slice 2): the
 * {@code (id, organizationId)} lookup finds a summary only under its own tenant, the tenant+patient listing is
 * newest-first and never returns another tenant's rows, and the diagnosis must be a real catalog code (the FK
 * to the global {@code medical_code}). Runs without the {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class ClinicalSummaryRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired MedicalCodeRepository medicalCodeRepository;
    @Autowired ClinicalSummaryRepository summaryRepository;

    @Test
    void lookup_and_listing_are_scoped_to_the_owning_tenant_and_newest_first() {
        Organization north = organizationRepository.save(new Organization("NorthCare (cs-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (cs-test)"));
        AppUser author = appUserRepository.save(new AppUser("cs-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(north.getId(), "NC-9001", "North Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.ICD10CM, "E11.9", "Type 2 diabetes"));

        ClinicalSummary older = summaryRepository.save(new ClinicalSummary(
                north.getId(), patient.getId(), ClinicalSummaryType.ENCOUNTER,
                LocalDate.now().minusMonths(2), "Older visit", CodeSystem.ICD10CM, "E11.9", "note", author.getId()));
        ClinicalSummary newer = summaryRepository.save(new ClinicalSummary(
                north.getId(), patient.getId(), ClinicalSummaryType.DIAGNOSIS,
                LocalDate.now().minusDays(3), "Newer visit", CodeSystem.ICD10CM, "E11.9", "note", author.getId()));

        assertTrue(summaryRepository.findByIdAndOrganizationId(newer.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the summary");
        assertTrue(summaryRepository.findByIdAndOrganizationId(newer.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");

        List<ClinicalSummary> northList = summaryRepository
                .findByOrganizationIdAndPatientIdOrderByEncounterDateDescCreatedAtDesc(north.getId(), patient.getId());
        assertEquals(List.of(newer.getId(), older.getId()),
                northList.stream().map(ClinicalSummary::getId).toList(),
                "listing is newest encounter first");
        assertEquals(0, summaryRepository
                .findByOrganizationIdAndPatientIdOrderByEncounterDateDescCreatedAtDesc(green.getId(), patient.getId())
                .size(), "a summary never appears under another tenant's id");
    }

    @Test
    void the_diagnosis_must_be_a_real_catalog_code() {
        Organization org = organizationRepository.save(new Organization("FK Org (cs-test)"));
        AppUser author = appUserRepository.save(new AppUser("fk-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "FK-1", "FK Patient", LocalDate.of(1990, 1, 1)));

        // No such code seeded → the FK to medical_code rejects the insert.
        assertThrows(DataIntegrityViolationException.class, () -> summaryRepository.saveAndFlush(
                new ClinicalSummary(org.getId(), patient.getId(), ClinicalSummaryType.DIAGNOSIS,
                        LocalDate.now(), "Bad code", CodeSystem.ICD10CM, "NOPE.0", "note", author.getId())));
    }
}
