package com.healthcloud.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
}
