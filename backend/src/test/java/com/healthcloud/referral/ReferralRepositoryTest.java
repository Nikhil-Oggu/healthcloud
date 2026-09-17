package com.healthcloud.referral;

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
 * Tenant-key isolation and structural integrity for the referral aggregate (Phase 6 slice 6): the
 * {@code (id, organizationId)} lookup is tenant-scoped, the referral number is unique <i>within</i> a tenant
 * (reusable across tenants), history loads in order, and the reason must be a real catalog code (the FK to the
 * global {@code medical_code}). Runs without the {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class ReferralRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired MedicalCodeRepository medicalCodeRepository;
    @Autowired ReferralRepository referralRepository;
    @Autowired ReferralStatusHistoryRepository historyRepository;

    @Test
    void lookup_is_tenant_scoped_and_the_number_is_unique_within_a_tenant() {
        Organization north = organizationRepository.save(new Organization("NorthCare (ref-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (ref-test)"));
        AppUser author = appUserRepository.save(new AppUser("ref-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(north.getId(), "NC-9001", "North Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.ICD10CM, "I10", "Hypertension"));

        Referral referral = referralRepository.saveAndFlush(new Referral(
                north.getId(), patient.getId(), "REF-1", "Cardiology", "ICD10CM", "I10", author.getId()));

        assertTrue(referralRepository.findByIdAndOrganizationId(referral.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the referral");
        assertTrue(referralRepository.findByIdAndOrganizationId(referral.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");

        // The same referral number in the SAME tenant is rejected.
        assertThrows(DataIntegrityViolationException.class,
                () -> referralRepository.saveAndFlush(new Referral(
                        north.getId(), patient.getId(), "REF-1", "Cardiology", "ICD10CM", "I10", author.getId())));
    }

    @Test
    void a_referral_number_may_be_reused_in_a_different_tenant() {
        Organization north = organizationRepository.save(new Organization("North (ref-num)"));
        Organization green = organizationRepository.save(new Organization("Green (ref-num)"));
        AppUser author = appUserRepository.save(new AppUser("refnum-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient northPatient = patientRepository.save(
                new Patient(north.getId(), "NC-9101", "North P", LocalDate.of(1990, 1, 1)));
        Patient greenPatient = patientRepository.save(
                new Patient(green.getId(), "GV-9101", "Green P", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.ICD10CM, "I10", "Hypertension"));

        referralRepository.saveAndFlush(new Referral(
                north.getId(), northPatient.getId(), "REF-SHARED", "Cardiology", "ICD10CM", "I10", author.getId()));
        // Same number, different tenant — allowed (uniqueness is per organization).
        referralRepository.saveAndFlush(new Referral(
                green.getId(), greenPatient.getId(), "REF-SHARED", "Cardiology", "ICD10CM", "I10", author.getId()));
    }

    @Test
    void history_loads_oldest_first() {
        Organization org = organizationRepository.save(new Organization("Hist Org (ref-test)"));
        AppUser actor = appUserRepository.save(new AppUser("refh-" + UUID.randomUUID() + "@ex.org", "Actor"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-9201", "Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.ICD10CM, "I10", "Hypertension"));
        Referral referral = referralRepository.save(new Referral(
                org.getId(), patient.getId(), "REF-H", "Cardiology", "ICD10CM", "I10", actor.getId()));

        historyRepository.save(new ReferralStatusHistory(
                org.getId(), referral.getId(), null, ReferralStatus.REQUESTED, actor.getId(), null, null));
        historyRepository.save(new ReferralStatusHistory(
                org.getId(), referral.getId(), ReferralStatus.REQUESTED, ReferralStatus.APPROVED,
                actor.getId(), null, null));

        List<ReferralStatusHistory> history = historyRepository
                .findByOrganizationIdAndReferralIdOrderByCreatedAtAsc(org.getId(), referral.getId());
        assertEquals(
                List.of(ReferralStatus.REQUESTED, ReferralStatus.APPROVED),
                history.stream().map(ReferralStatusHistory::getToStatus).toList(),
                "history reads chronologically");
    }

    @Test
    void a_reason_must_be_a_real_catalog_code() {
        Organization org = organizationRepository.save(new Organization("FK Org (ref-test)"));
        AppUser author = appUserRepository.save(new AppUser("reffk-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-9301", "Patient", LocalDate.of(1990, 1, 1)));

        // No such code seeded → the FK to medical_code rejects the insert.
        assertThrows(DataIntegrityViolationException.class,
                () -> referralRepository.saveAndFlush(new Referral(
                        org.getId(), patient.getId(), "REF-FK", "Cardiology", "ICD10CM", "Z99.9", author.getId())));
    }

    @Test
    void searchAll_pages_counts_sorts_and_filters_by_status_in_the_database() {
        Organization org = organizationRepository.save(new Organization("Ref Search Org"));
        AppUser author = appUserRepository.save(new AppUser("rfs-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-9401", "Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.ICD10CM, "I10", "Hypertension"));

        savedReferral(org, patient, author, "REF-S1", ReferralStatus.REQUESTED);
        savedReferral(org, patient, author, "REF-S2", ReferralStatus.REQUESTED);
        savedReferral(org, patient, author, "REF-S3", ReferralStatus.REQUESTED);
        savedReferral(org, patient, author, "REF-S4", ReferralStatus.APPROVED);

        Page<Referral> firstPage = referralRepository.searchAll(
                org.getId(), null, null, PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "referralNumber")));
        assertEquals(4, firstPage.getTotalElements(), "the count spans every matching row, not just the page");
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(List.of("REF-S1", "REF-S2"),
                firstPage.getContent().stream().map(Referral::getReferralNumber).toList(),
                "sorted by referral number ascending, in the database");

        Page<Referral> approved = referralRepository.searchAll(
                org.getId(), ReferralStatus.APPROVED, null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(1, approved.getTotalElements());
        assertEquals("REF-S4", approved.getContent().get(0).getReferralNumber());
    }

    @Test
    void searchAll_filters_by_a_free_text_referral_number_case_insensitively() {
        Organization org = organizationRepository.save(new Organization("Ref FreeText Org"));
        AppUser author = appUserRepository.save(new AppUser("rft-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-9601", "Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.ICD10CM, "I10", "Hypertension"));

        savedReferral(org, patient, author, "REF-ALPHA1", ReferralStatus.REQUESTED);
        savedReferral(org, patient, author, "REF-ALPHA2", ReferralStatus.REQUESTED);
        savedReferral(org, patient, author, "REF-BETA1", ReferralStatus.REQUESTED);

        Page<Referral> alphas = referralRepository.searchAll(
                org.getId(), null, "%alpha%", PageRequest.of(0, 10, Sort.by("referralNumber")));
        assertEquals(2, alphas.getTotalElements(), "both ALPHA referrals match (lowercase term, uppercase numbers)");
        assertEquals(List.of("REF-ALPHA1", "REF-ALPHA2"),
                alphas.getContent().stream().map(Referral::getReferralNumber).toList());
        assertEquals(0, referralRepository.searchAll(
                org.getId(), null, "%zzz%", PageRequest.of(0, 10, Sort.by("referralNumber"))).getTotalElements(),
                "a non-matching term returns nothing");
        assertEquals(3, referralRepository.searchAll(
                org.getId(), null, null, PageRequest.of(0, 10, Sort.by("referralNumber"))).getTotalElements(),
                "a null term disables the search clause");
    }

    @Test
    void searchForPatients_scopes_to_the_given_patients_and_is_tenant_scoped() {
        Organization org = organizationRepository.save(new Organization("Ref Scope Org"));
        AppUser author = appUserRepository.save(new AppUser("rfsc-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient p1 = patientRepository.save(new Patient(org.getId(), "NC-9501", "P1", LocalDate.of(1990, 1, 1)));
        Patient p2 = patientRepository.save(new Patient(org.getId(), "NC-9502", "P2", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.ICD10CM, "I10", "Hypertension"));

        savedReferral(org, p1, author, "REF-P1A", ReferralStatus.REQUESTED);
        savedReferral(org, p1, author, "REF-P1B", ReferralStatus.REQUESTED);
        savedReferral(org, p2, author, "REF-P2A", ReferralStatus.REQUESTED);

        Page<Referral> onlyP1 = referralRepository.searchForPatients(
                org.getId(), Set.of(p1.getId()), null, null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(2, onlyP1.getTotalElements(), "only the requested patient's referrals are visible");
        assertTrue(onlyP1.getContent().stream().allMatch(r -> r.getPatientId().equals(p1.getId())));

        Organization other = organizationRepository.save(new Organization("Ref Other Org"));
        Page<Referral> crossTenant = referralRepository.searchForPatients(
                other.getId(), Set.of(p1.getId(), p2.getId()), null, null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(0, crossTenant.getTotalElements());
    }

    /** Save a referral with an explicit status (default on creation is REQUESTED). */
    private Referral savedReferral(Organization org, Patient patient, AppUser author,
                                   String number, ReferralStatus status) {
        Referral referral = new Referral(
                org.getId(), patient.getId(), number, "Cardiology", "ICD10CM", "I10", author.getId());
        referral.setStatus(status);
        return referralRepository.saveAndFlush(referral);
    }
}
