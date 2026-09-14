package com.healthcloud.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Tenant-key isolation for the patient-document repository (Phase 3 slice 14): the {@code (id, organizationId)}
 * lookup finds a document only under its own tenant, tenant+patient listing never returns another tenant's
 * rows, and the unique storage key is enforced by the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class PatientDocumentRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired PatientDocumentRepository documentRepository;

    @Test
    void document_lookup_and_listing_are_scoped_to_the_owning_tenant() {
        Organization north = organizationRepository.save(new Organization("NorthCare (doc-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (doc-test)"));
        AppUser uploader = appUserRepository.save(new AppUser("doc-" + UUID.randomUUID() + "@ex.org", "Uploader"));
        Patient northPatient = patientRepository.save(
                new Patient(north.getId(), "NC-8001", "North Patient", LocalDate.of(1990, 1, 1)));

        PatientDocument doc = documentRepository.save(new PatientDocument(
                north.getId(), northPatient.getId(), "a.txt", "text/plain", 3,
                "key-" + UUID.randomUUID(), DocumentScanStatus.CLEAN, uploader.getId()));

        assertTrue(documentRepository.findByIdAndOrganizationId(doc.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the document");
        assertTrue(documentRepository.findByIdAndOrganizationId(doc.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");

        assertEquals(1, documentRepository
                .findByOrganizationIdAndPatientIdOrderByUploadedAtDesc(north.getId(), northPatient.getId()).size());
        assertEquals(0, documentRepository
                .findByOrganizationIdAndPatientIdOrderByUploadedAtDesc(green.getId(), northPatient.getId()).size());
    }

    @Test
    void a_storage_key_is_unique() {
        Organization org = organizationRepository.save(new Organization("Key Org (doc-test)"));
        AppUser uploader = appUserRepository.save(new AppUser("key-" + UUID.randomUUID() + "@ex.org", "Uploader"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "KEY-1", "Key Patient", LocalDate.of(1990, 1, 1)));
        String storageKey = "shared-key-" + UUID.randomUUID();

        documentRepository.saveAndFlush(new PatientDocument(
                org.getId(), patient.getId(), "a.txt", "text/plain", 1, storageKey,
                DocumentScanStatus.CLEAN, uploader.getId()));

        assertThrows(DataIntegrityViolationException.class, () -> documentRepository.saveAndFlush(
                new PatientDocument(org.getId(), patient.getId(), "b.txt", "text/plain", 1, storageKey,
                        DocumentScanStatus.CLEAN, uploader.getId())));
    }
}
