package com.healthcloud.appeal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.claim.Claim;
import com.healthcloud.claim.ClaimRepository;
import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientRepository;
import java.math.BigDecimal;
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
 * Tenant-key isolation and structural integrity for the appeal aggregate (Phase 6 slice 8): the
 * {@code (id, organizationId)} lookup is tenant-scoped, the appeal number is unique <i>within</i> a tenant
 * (reusable across tenants), the open-appeal-per-claim existence check works, history loads in order, and the
 * appeal must reference a real in-tenant claim (the FK-with-org). Runs without the {@code local} profile so the
 * seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class AppealRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired ClaimRepository claimRepository;
    @Autowired AppealRepository appealRepository;
    @Autowired AppealStatusHistoryRepository historyRepository;

    private Claim claim(Organization org, Patient patient, AppUser author, String number) {
        return claimRepository.save(new Claim(
                org.getId(), patient.getId(), number, LocalDate.now().minusWeeks(2),
                new BigDecimal("150.00"), author.getId()));
    }

    @Test
    void lookup_is_tenant_scoped_and_the_number_is_unique_within_a_tenant() {
        Organization north = organizationRepository.save(new Organization("NorthCare (apl-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (apl-test)"));
        AppUser author = appUserRepository.save(new AppUser("apl-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(north.getId(), "NC-8001", "North Patient", LocalDate.of(1990, 1, 1)));
        Claim c = claim(north, patient, author, "CLM-APL-1");

        Appeal appeal = appealRepository.saveAndFlush(new Appeal(
                north.getId(), c.getId(), patient.getId(), "APL-1", "Rejected in error", author.getId()));

        assertTrue(appealRepository.findByIdAndOrganizationId(appeal.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the appeal");
        assertTrue(appealRepository.findByIdAndOrganizationId(appeal.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");

        // The same appeal number in the SAME tenant is rejected.
        assertThrows(DataIntegrityViolationException.class,
                () -> appealRepository.saveAndFlush(new Appeal(
                        north.getId(), c.getId(), patient.getId(), "APL-1", "Dup", author.getId())));
    }

    @Test
    void open_appeal_existence_check_is_scoped_to_claim_and_status() {
        Organization org = organizationRepository.save(new Organization("Open Org (apl-test)"));
        AppUser author = appUserRepository.save(new AppUser("aplopen-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8101", "Patient", LocalDate.of(1990, 1, 1)));
        Claim c = claim(org, patient, author, "CLM-APL-OPEN");

        assertFalse(appealRepository.existsByOrganizationIdAndClaimIdAndStatus(
                org.getId(), c.getId(), AppealStatus.SUBMITTED), "no open appeal yet");

        appealRepository.saveAndFlush(new Appeal(
                org.getId(), c.getId(), patient.getId(), "APL-OPEN", "First appeal", author.getId()));

        assertTrue(appealRepository.existsByOrganizationIdAndClaimIdAndStatus(
                org.getId(), c.getId(), AppealStatus.SUBMITTED), "an open (SUBMITTED) appeal is detected");
        // A terminal status does not count as an open appeal.
        assertFalse(appealRepository.existsByOrganizationIdAndClaimIdAndStatus(
                org.getId(), c.getId(), AppealStatus.WITHDRAWN), "a WITHDRAWN appeal is not open");
    }

    @Test
    void history_loads_oldest_first() {
        Organization org = organizationRepository.save(new Organization("Hist Org (apl-test)"));
        AppUser actor = appUserRepository.save(new AppUser("aplh-" + UUID.randomUUID() + "@ex.org", "Actor"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8201", "Patient", LocalDate.of(1990, 1, 1)));
        Claim c = claim(org, patient, actor, "CLM-APL-H");
        Appeal appeal = appealRepository.save(new Appeal(
                org.getId(), c.getId(), patient.getId(), "APL-H", "Appeal", actor.getId()));

        historyRepository.save(new AppealStatusHistory(
                org.getId(), appeal.getId(), null, AppealStatus.SUBMITTED, actor.getId(), null, null));
        historyRepository.save(new AppealStatusHistory(
                org.getId(), appeal.getId(), AppealStatus.SUBMITTED, AppealStatus.UPHELD, actor.getId(), null, null));

        List<AppealStatusHistory> history = historyRepository
                .findByOrganizationIdAndAppealIdOrderByCreatedAtAsc(org.getId(), appeal.getId());
        assertEquals(
                List.of(AppealStatus.SUBMITTED, AppealStatus.UPHELD),
                history.stream().map(AppealStatusHistory::getToStatus).toList(),
                "history reads chronologically");
    }

    @Test
    void an_appeal_must_reference_a_real_in_tenant_claim() {
        Organization org = organizationRepository.save(new Organization("FK Org (apl-test)"));
        AppUser author = appUserRepository.save(new AppUser("aplfk-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8301", "Patient", LocalDate.of(1990, 1, 1)));

        // No such claim → the composite FK to claim(id, organization_id) rejects the insert.
        assertThrows(DataIntegrityViolationException.class,
                () -> appealRepository.saveAndFlush(new Appeal(
                        org.getId(), UUID.randomUUID(), patient.getId(), "APL-FK", "Bad claim", author.getId())));
    }
}
