package com.healthcloud.relationship;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-level invariant for provider-patient assignments (Phase 3 slice 4): the partial unique index
 * enforces "at most one CURRENT (ACTIVE/PENDING) assignment per (patient, provider)" — the "already
 * assigned" backstop and the guard against racing assigns. A revoked row frees the pair.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class ProviderPatientAssignmentRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired ProviderPatientAssignmentRepository assignmentRepository;

    private ProviderPatientAssignment active(UUID orgId, UUID patientId, UUID providerId, UUID assignerId) {
        return new ProviderPatientAssignment(orgId, patientId, providerId, assignerId,
                ProviderPatientAssignmentStatus.ACTIVE, LocalDate.now(), null);
    }

    @Test
    void two_current_assignments_for_the_same_pair_are_rejected() {
        Organization org = organizationRepository.save(new Organization("PPA Org (ppa-test)"));
        Patient patient = patientRepository.saveAndFlush(
                new Patient(org.getId(), "PPA-1", "PPA Patient", LocalDate.of(1990, 1, 1)));
        AppUser provider = appUserRepository.saveAndFlush(new AppUser("ppa.provider@x.test", "PPA Provider"));
        AppUser assigner = appUserRepository.saveAndFlush(new AppUser("ppa.assigner@x.test", "PPA Assigner"));

        assignmentRepository.saveAndFlush(active(org.getId(), patient.getId(), provider.getId(), assigner.getId()));

        assertThrows(DataIntegrityViolationException.class, () -> assignmentRepository.saveAndFlush(
                active(org.getId(), patient.getId(), provider.getId(), assigner.getId())));
    }

    @Test
    void revoking_frees_the_pair() {
        Organization org = organizationRepository.save(new Organization("PPA Org2 (ppa-test)"));
        Patient patient = patientRepository.saveAndFlush(
                new Patient(org.getId(), "PPA-2", "PPA Patient 2", LocalDate.of(1991, 2, 2)));
        AppUser provider = appUserRepository.saveAndFlush(new AppUser("ppa.provider2@x.test", "PPA Provider 2"));
        AppUser assigner = appUserRepository.saveAndFlush(new AppUser("ppa.assigner2@x.test", "PPA Assigner 2"));

        ProviderPatientAssignment first =
                assignmentRepository.saveAndFlush(active(org.getId(), patient.getId(), provider.getId(), assigner.getId()));
        first.revoke();
        assignmentRepository.saveAndFlush(first);

        assertDoesNotThrow(() -> assignmentRepository.saveAndFlush(
                active(org.getId(), patient.getId(), provider.getId(), assigner.getId())));
    }
}
