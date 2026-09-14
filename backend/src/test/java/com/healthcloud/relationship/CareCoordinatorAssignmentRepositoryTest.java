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
 * Database-level invariant for care-coordinator assignments (Phase 3 slice 7): the partial unique index
 * enforces "at most one CURRENT (ACTIVE/PENDING) assignment per (patient, coordinator)" — the "already
 * assigned" backstop and the guard against racing assigns. A revoked row frees the pair.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class CareCoordinatorAssignmentRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired CareCoordinatorAssignmentRepository assignmentRepository;

    private CareCoordinatorAssignment active(UUID orgId, UUID patientId, UUID coordinatorId, UUID assignerId) {
        return new CareCoordinatorAssignment(orgId, patientId, coordinatorId, assignerId,
                CareCoordinatorAssignmentStatus.ACTIVE, LocalDate.now(), null);
    }

    @Test
    void two_current_assignments_for_the_same_pair_are_rejected() {
        Organization org = organizationRepository.save(new Organization("CCA Org (cca-test)"));
        Patient patient = patientRepository.saveAndFlush(
                new Patient(org.getId(), "CCA-1", "CCA Patient", LocalDate.of(1990, 1, 1)));
        AppUser coordinator = appUserRepository.saveAndFlush(new AppUser("cca.coordinator@x.test", "CCA Coordinator"));
        AppUser assigner = appUserRepository.saveAndFlush(new AppUser("cca.assigner@x.test", "CCA Assigner"));

        assignmentRepository.saveAndFlush(active(org.getId(), patient.getId(), coordinator.getId(), assigner.getId()));

        assertThrows(DataIntegrityViolationException.class, () -> assignmentRepository.saveAndFlush(
                active(org.getId(), patient.getId(), coordinator.getId(), assigner.getId())));
    }

    @Test
    void revoking_frees_the_pair() {
        Organization org = organizationRepository.save(new Organization("CCA Org2 (cca-test)"));
        Patient patient = patientRepository.saveAndFlush(
                new Patient(org.getId(), "CCA-2", "CCA Patient 2", LocalDate.of(1991, 2, 2)));
        AppUser coordinator = appUserRepository.saveAndFlush(new AppUser("cca.coordinator2@x.test", "CCA Coordinator 2"));
        AppUser assigner = appUserRepository.saveAndFlush(new AppUser("cca.assigner2@x.test", "CCA Assigner 2"));

        CareCoordinatorAssignment first =
                assignmentRepository.saveAndFlush(active(org.getId(), patient.getId(), coordinator.getId(), assigner.getId()));
        first.revoke();
        assignmentRepository.saveAndFlush(first);

        assertDoesNotThrow(() -> assignmentRepository.saveAndFlush(
                active(org.getId(), patient.getId(), coordinator.getId(), assigner.getId())));
    }
}
