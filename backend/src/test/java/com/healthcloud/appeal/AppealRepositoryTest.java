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

    @Test
    void searchAll_pages_counts_and_filters_by_status_in_the_database() {
        Organization org = organizationRepository.save(new Organization("Apl Search Org"));
        AppUser author = appUserRepository.save(new AppUser("apls-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-8401", "Patient", LocalDate.of(1990, 1, 1)));
        Claim c1 = claim(org, patient, author, "CLM-AS1");
        Claim c2 = claim(org, patient, author, "CLM-AS2");
        Claim c3 = claim(org, patient, author, "CLM-AS3");

        savedAppeal(org, c1, patient, author, "APL-S1", AppealStatus.SUBMITTED);
        savedAppeal(org, c2, patient, author, "APL-S2", AppealStatus.SUBMITTED);
        savedAppeal(org, c3, patient, author, "APL-S3", AppealStatus.UPHELD);

        Page<Appeal> firstPage = appealRepository.searchAll(
                org.getId(), null, PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "appealNumber")));
        assertEquals(3, firstPage.getTotalElements(), "the count spans every matching row, not just the page");
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(List.of("APL-S1", "APL-S2"),
                firstPage.getContent().stream().map(Appeal::getAppealNumber).toList());

        Page<Appeal> upheld = appealRepository.searchAll(
                org.getId(), AppealStatus.UPHELD, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(1, upheld.getTotalElements());
        assertEquals("APL-S3", upheld.getContent().get(0).getAppealNumber());
    }

    @Test
    void searchForPatients_and_searchForClaim_scope_correctly() {
        Organization org = organizationRepository.save(new Organization("Apl Scope Org"));
        AppUser author = appUserRepository.save(new AppUser("aplsc-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient p1 = patientRepository.save(new Patient(org.getId(), "NC-8501", "P1", LocalDate.of(1990, 1, 1)));
        Patient p2 = patientRepository.save(new Patient(org.getId(), "NC-8502", "P2", LocalDate.of(1990, 1, 1)));
        Claim c1 = claim(org, p1, author, "CLM-ASP1");
        Claim c2 = claim(org, p1, author, "CLM-ASP2");
        Claim c3 = claim(org, p2, author, "CLM-ASP3");
        savedAppeal(org, c1, p1, author, "APL-SP1", AppealStatus.SUBMITTED);
        savedAppeal(org, c2, p1, author, "APL-SP2", AppealStatus.SUBMITTED);
        savedAppeal(org, c3, p2, author, "APL-SP3", AppealStatus.SUBMITTED);

        Page<Appeal> onlyP1 = appealRepository.searchForPatients(
                org.getId(), Set.of(p1.getId()), null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(2, onlyP1.getTotalElements(), "only the requested patients' appeals are visible");

        Page<Appeal> onlyC1 = appealRepository.searchForClaim(
                org.getId(), c1.getId(), null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(1, onlyC1.getTotalElements(), "the claim filter narrows to that claim's appeals");
        assertEquals("APL-SP1", onlyC1.getContent().get(0).getAppealNumber());

        Organization other = organizationRepository.save(new Organization("Apl Other Org"));
        Page<Appeal> crossTenant = appealRepository.searchForClaim(
                other.getId(), c1.getId(), null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(0, crossTenant.getTotalElements());
    }

    /** Save an appeal with an explicit status (default on creation is SUBMITTED). */
    private Appeal savedAppeal(Organization org, Claim claim, Patient patient, AppUser author,
                               String number, AppealStatus status) {
        Appeal appeal = new Appeal(
                org.getId(), claim.getId(), patient.getId(), number, "Disputed", author.getId());
        appeal.setStatus(status);
        return appealRepository.saveAndFlush(appeal);
    }
}
