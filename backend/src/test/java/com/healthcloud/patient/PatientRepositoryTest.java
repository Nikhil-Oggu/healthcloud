package com.healthcloud.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-key isolation for the patient repository (Phase 2 slice 1): the {@code (id, organizationId)}
 * lookup — the one business code uses — finds a patient only under its own tenant, and tenant-scoped
 * listing never returns another tenant's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class PatientRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;

    @Test
    void patient_lookup_is_scoped_to_the_owning_tenant() {
        Organization north = organizationRepository.save(new Organization("NorthCare (pt-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (pt-test)"));

        Patient northPatient = patientRepository.save(
                new Patient(north.getId(), "NC-9001", "North Patient", LocalDate.of(1990, 1, 1)));
        patientRepository.save(
                new Patient(green.getId(), "GV-9001", "Green Patient", LocalDate.of(1991, 2, 2)));

        // Same-tenant lookup finds it; cross-tenant lookup of the SAME id returns nothing (→ 404).
        assertTrue(patientRepository.findByIdAndOrganizationId(northPatient.getId(), north.getId()).isPresent(),
                "same-tenant lookup should find the patient");
        assertTrue(patientRepository.findByIdAndOrganizationId(northPatient.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup (other org + this patient id) must return nothing");

        // Tenant-scoped listing returns only the caller-org's patients.
        assertEquals(1, patientRepository.findByOrganizationIdOrderByFullNameAsc(north.getId()).size());
        assertEquals(1, patientRepository.findByOrganizationIdOrderByFullNameAsc(green.getId()).size());
    }

    @Test
    void duplicate_mrn_within_a_tenant_is_rejected_by_the_database() {
        Organization org = organizationRepository.save(new Organization("Dupe Org (pt-test)"));
        patientRepository.saveAndFlush(new Patient(org.getId(), "DUP-1", "First", LocalDate.of(1990, 1, 1)));

        // Proves the UNIQUE(organization_id, mrn) constraint is real — the DB is the backstop the
        // GlobalExceptionHandler maps to 409 when the service pre-check loses a race.
        assertThrows(DataIntegrityViolationException.class,
                () -> patientRepository.saveAndFlush(
                        new Patient(org.getId(), "DUP-1", "Second", LocalDate.of(1991, 2, 2))));
    }

    @Test
    void a_patient_can_be_linked_to_a_login_and_looked_up_by_it() {
        Organization org = organizationRepository.save(new Organization("Link Org (pt-test)"));
        AppUser user = appUserRepository.save(new AppUser("self-" + UUID.randomUUID() + "@ex.org", "Self Patient"));

        Patient patient = new Patient(org.getId(), "LNK-1", "Self Patient", LocalDate.of(1990, 1, 1));
        patient.setAppUserId(user.getId());
        Patient saved = patientRepository.saveAndFlush(patient);

        assertTrue(patientRepository.findByOrganizationIdAndAppUserId(org.getId(), user.getId()).isPresent(),
                "the linked profile is found by its login");
        assertEquals(saved.getId(),
                patientRepository.findByOrganizationIdAndAppUserId(org.getId(), user.getId()).orElseThrow().getId());
    }

    @Test
    void a_login_can_be_linked_to_at_most_one_patient() {
        Organization org = organizationRepository.save(new Organization("OneLink Org (pt-test)"));
        AppUser user = appUserRepository.save(new AppUser("one-" + UUID.randomUUID() + "@ex.org", "One Patient"));

        Patient first = new Patient(org.getId(), "ONE-1", "First Profile", LocalDate.of(1990, 1, 1));
        first.setAppUserId(user.getId());
        patientRepository.saveAndFlush(first);

        // The partial unique index on app_user_id backstops the "one profile per login" invariant.
        Patient second = new Patient(org.getId(), "ONE-2", "Second Profile", LocalDate.of(1991, 2, 2));
        second.setAppUserId(user.getId());
        assertThrows(DataIntegrityViolationException.class, () -> patientRepository.saveAndFlush(second));
    }
}
