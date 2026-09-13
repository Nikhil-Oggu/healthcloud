package com.healthcloud.consent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.healthcloud.TestcontainersConfiguration;
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
 * Database-level invariants for consent directives (Phase 3 slice 1): the partial unique index enforces
 * "at most one CURRENT (ACTIVE/SCHEDULED) directive per natural key" — the backstop the service relies on
 * when it supersedes-then-inserts, and against racing grants. A superseded row frees the slot again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class ConsentDirectiveRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired ConsentDirectiveRepository consentRepository;

    private ConsentDirective active(UUID orgId, UUID patientId, UUID actor) {
        return new ConsentDirective(orgId, patientId, UUID.randomUUID(), ConsentEffect.GRANT,
                ConsentPurpose.CARE_COORDINATION, ConsentDataCategory.CLINICAL_CONTEXT,
                ConsentScopeType.ORGANIZATION, null, LocalDate.now(), null, ConsentStatus.ACTIVE, 1, actor);
    }

    @Test
    void two_current_directives_for_the_same_natural_key_are_rejected() {
        Organization org = organizationRepository.save(new Organization("Consent Org (cn-test)"));
        Patient patient = patientRepository.saveAndFlush(
                new Patient(org.getId(), "CN-1", "Consent Patient", LocalDate.of(1990, 1, 1)));
        UUID actor = UUID.randomUUID();

        consentRepository.saveAndFlush(active(org.getId(), patient.getId(), actor));

        // A second CURRENT row with the same (org, patient, purpose, category, scope) violates the index.
        assertThrows(DataIntegrityViolationException.class,
                () -> consentRepository.saveAndFlush(active(org.getId(), patient.getId(), actor)));
    }

    @Test
    void superseding_the_current_row_frees_the_slot() {
        Organization org = organizationRepository.save(new Organization("Consent Org2 (cn-test)"));
        Patient patient = patientRepository.saveAndFlush(
                new Patient(org.getId(), "CN-2", "Consent Patient 2", LocalDate.of(1991, 2, 2)));
        UUID actor = UUID.randomUUID();

        ConsentDirective first = consentRepository.saveAndFlush(active(org.getId(), patient.getId(), actor));
        first.supersede();
        consentRepository.saveAndFlush(first);

        // With the first row SUPERSEDED, a new ACTIVE row for the same natural key is allowed.
        assertDoesNotThrow(
                () -> consentRepository.saveAndFlush(active(org.getId(), patient.getId(), actor)));
    }
}
