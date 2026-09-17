package com.healthcloud.claim;

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
 * Tenant-key isolation and structural integrity for the claim aggregate (Phase 4 slice 3): the
 * {@code (id, organizationId)} header lookup is tenant-scoped, the claim number is unique <i>within</i> a
 * tenant (reusable across tenants), lines load in order and their number is unique per claim, and a line's
 * procedure must be a real catalog code (the FK to the global {@code medical_code}). Runs without the
 * {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class ClaimRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired MedicalCodeRepository medicalCodeRepository;
    @Autowired ClaimRepository claimRepository;
    @Autowired ClaimLineRepository claimLineRepository;

    @Test
    void header_lookup_is_tenant_scoped_and_the_number_is_unique_within_a_tenant() {
        Organization north = organizationRepository.save(new Organization("NorthCare (clm-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (clm-test)"));
        AppUser author = appUserRepository.save(new AppUser("clm-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(north.getId(), "NC-7001", "North Patient", LocalDate.of(1990, 1, 1)));

        Claim claim = claimRepository.saveAndFlush(new Claim(
                north.getId(), patient.getId(), "CLM-1", LocalDate.now(), new BigDecimal("10.00"), author.getId()));

        assertTrue(claimRepository.findByIdAndOrganizationId(claim.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the claim");
        assertTrue(claimRepository.findByIdAndOrganizationId(claim.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");

        // The same claim number in the SAME tenant is rejected...
        assertThrows(DataIntegrityViolationException.class, () -> claimRepository.saveAndFlush(new Claim(
                north.getId(), patient.getId(), "CLM-1", LocalDate.now(), BigDecimal.ZERO, author.getId())));
    }

    @Test
    void a_claim_number_may_be_reused_in_a_different_tenant() {
        Organization north = organizationRepository.save(new Organization("North (num-test)"));
        Organization green = organizationRepository.save(new Organization("Green (num-test)"));
        AppUser author = appUserRepository.save(new AppUser("num-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient northPatient = patientRepository.save(
                new Patient(north.getId(), "NC-7101", "North P", LocalDate.of(1990, 1, 1)));
        Patient greenPatient = patientRepository.save(
                new Patient(green.getId(), "GV-7101", "Green P", LocalDate.of(1990, 1, 1)));

        claimRepository.saveAndFlush(new Claim(
                north.getId(), northPatient.getId(), "CLM-SHARED", LocalDate.now(), BigDecimal.ZERO, author.getId()));
        // Same number, different tenant — allowed (uniqueness is per organization).
        claimRepository.saveAndFlush(new Claim(
                green.getId(), greenPatient.getId(), "CLM-SHARED", LocalDate.now(), BigDecimal.ZERO, author.getId()));
    }

    @Test
    void lines_load_in_order_and_the_line_number_is_unique_per_claim() {
        Organization org = organizationRepository.save(new Organization("Lines Org (clm-test)"));
        AppUser author = appUserRepository.save(new AppUser("ln-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-7201", "Patient", LocalDate.of(1990, 1, 1)));
        medicalCodeRepository.save(new MedicalCode(CodeSystem.CPT, "99213", "Office visit"));
        Claim claim = claimRepository.save(new Claim(
                org.getId(), patient.getId(), "CLM-LN", LocalDate.now(), new BigDecimal("30.00"), author.getId()));

        claimLineRepository.save(new ClaimLine(
                org.getId(), claim.getId(), 1, CodeSystem.CPT, "99213", 1, new BigDecimal("10.00")));
        claimLineRepository.save(new ClaimLine(
                org.getId(), claim.getId(), 2, CodeSystem.CPT, "99213", 2, new BigDecimal("20.00")));

        List<ClaimLine> lines = claimLineRepository
                .findByOrganizationIdAndClaimIdOrderByLineNumberAsc(org.getId(), claim.getId());
        assertEquals(List.of(1, 2), lines.stream().map(ClaimLine::getLineNumber).toList(), "lines are in order");

        // A duplicate line number within the claim is rejected.
        assertThrows(DataIntegrityViolationException.class, () -> claimLineRepository.saveAndFlush(new ClaimLine(
                org.getId(), claim.getId(), 1, CodeSystem.CPT, "99213", 1, new BigDecimal("5.00"))));
    }

    @Test
    void searchAll_pages_counts_sorts_and_filters_by_status_in_the_database() {
        Organization org = organizationRepository.save(new Organization("Search Org (clm-test)"));
        AppUser author = appUserRepository.save(new AppUser("srch-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-7401", "Patient", LocalDate.of(1990, 1, 1)));

        // Three DRAFT claims with ascending service dates, plus one SUBMITTED claim.
        savedClaim(org, patient, author, "CLM-S1", LocalDate.of(2026, 1, 1), ClaimStatus.DRAFT);
        savedClaim(org, patient, author, "CLM-S2", LocalDate.of(2026, 2, 1), ClaimStatus.DRAFT);
        savedClaim(org, patient, author, "CLM-S3", LocalDate.of(2026, 3, 1), ClaimStatus.DRAFT);
        savedClaim(org, patient, author, "CLM-S4", LocalDate.of(2026, 4, 1), ClaimStatus.SUBMITTED);

        // First page of size 2, newest service date first — the total counts all 4, sort picks the top 2.
        Page<Claim> firstPage = claimRepository.searchAll(
                org.getId(), null, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "serviceDate")));
        assertEquals(4, firstPage.getTotalElements(), "the count spans every matching row, not just the page");
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(List.of("CLM-S4", "CLM-S3"),
                firstPage.getContent().stream().map(Claim::getClaimNumber).toList(),
                "sorted by service date descending, in the database");
        assertTrue(firstPage.isFirst());

        // The status filter runs in SQL: only the SUBMITTED claim matches.
        Page<Claim> submitted = claimRepository.searchAll(
                org.getId(), ClaimStatus.SUBMITTED, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(1, submitted.getTotalElements());
        assertEquals("CLM-S4", submitted.getContent().get(0).getClaimNumber());
    }

    @Test
    void searchForPatients_scopes_to_the_given_patients_and_is_tenant_scoped() {
        Organization org = organizationRepository.save(new Organization("Scope Org (clm-test)"));
        AppUser author = appUserRepository.save(new AppUser("scp-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient p1 = patientRepository.save(new Patient(org.getId(), "NC-7501", "P1", LocalDate.of(1990, 1, 1)));
        Patient p2 = patientRepository.save(new Patient(org.getId(), "NC-7502", "P2", LocalDate.of(1990, 1, 1)));

        savedClaim(org, p1, author, "CLM-P1A", LocalDate.of(2026, 1, 1), ClaimStatus.DRAFT);
        savedClaim(org, p1, author, "CLM-P1B", LocalDate.of(2026, 2, 1), ClaimStatus.DRAFT);
        savedClaim(org, p2, author, "CLM-P2A", LocalDate.of(2026, 1, 1), ClaimStatus.DRAFT);

        Page<Claim> onlyP1 = claimRepository.searchForPatients(
                org.getId(), Set.of(p1.getId()), null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(2, onlyP1.getTotalElements(), "only the requested patient's claims are visible");
        assertTrue(onlyP1.getContent().stream().allMatch(c -> c.getPatientId().equals(p1.getId())));

        // A different tenant's org id finds nothing even for the same patient ids (secure by construction).
        Organization other = organizationRepository.save(new Organization("Other Org (clm-test)"));
        Page<Claim> crossTenant = claimRepository.searchForPatients(
                other.getId(), Set.of(p1.getId(), p2.getId()), null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(0, crossTenant.getTotalElements());
    }

    /** Save a claim with an explicit status (default on creation is DRAFT). */
    private Claim savedClaim(Organization org, Patient patient, AppUser author,
                             String number, LocalDate serviceDate, ClaimStatus status) {
        Claim claim = new Claim(
                org.getId(), patient.getId(), number, serviceDate, new BigDecimal("10.00"), author.getId());
        claim.setStatus(status);
        return claimRepository.saveAndFlush(claim);
    }

    @Test
    void a_procedure_must_be_a_real_catalog_code() {
        Organization org = organizationRepository.save(new Organization("FK Org (clm-test)"));
        AppUser author = appUserRepository.save(new AppUser("fkc-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-7301", "Patient", LocalDate.of(1990, 1, 1)));
        Claim claim = claimRepository.save(new Claim(
                org.getId(), patient.getId(), "CLM-FK", LocalDate.now(), new BigDecimal("5.00"), author.getId()));

        // No such code seeded → the FK to medical_code rejects the line insert.
        assertThrows(DataIntegrityViolationException.class, () -> claimLineRepository.saveAndFlush(new ClaimLine(
                org.getId(), claim.getId(), 1, CodeSystem.CPT, "00000", 1, new BigDecimal("5.00"))));
    }
}
