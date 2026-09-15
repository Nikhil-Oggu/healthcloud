package com.healthcloud.adjudication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.claim.Claim;
import com.healthcloud.claim.ClaimRepository;
import com.healthcloud.coding.CodeSystem;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-key isolation and the by-claim lookup for the adjudication repositories (Phase 5 slice 1): the
 * {@code (id, organizationId)} finder is tenant-scoped (→ secure 404 cross-tenant), the by-claim finder returns
 * the stored adjudication, and the line finder reads a claim's breakdown in line order. Runs without the
 * {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class AdjudicationRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired ClaimRepository claimRepository;
    @Autowired AdjudicationRepository adjudicationRepository;
    @Autowired AdjudicationLineRepository adjudicationLineRepository;

    @Test
    void lookup_is_tenant_scoped_and_by_claim() {
        Organization north = organizationRepository.save(new Organization("NorthCare (adj-test)"));
        Organization green = organizationRepository.save(new Organization("Green Valley (adj-test)"));
        AppUser actor = appUserRepository.save(new AppUser("adj-" + UUID.randomUUID() + "@ex.org", "Reviewer"));
        Patient patient = patientRepository.save(
                new Patient(north.getId(), "NC-7001", "North Patient", LocalDate.of(1990, 1, 1)));
        Claim claim = claimRepository.save(new Claim(
                north.getId(), patient.getId(), "CLM-ADJ0001", LocalDate.of(2026, 1, 10),
                new BigDecimal("195.50"), actor.getId()));

        Adjudication saved = adjudicationRepository.save(new Adjudication(
                north.getId(), claim.getId(), 1, AdjudicationOutcome.ADJUDICATED, null, null,
                new BigDecimal("195.50"), new BigDecimal("195.50"), new BigDecimal("39.10"),
                new BigDecimal("156.40"), actor.getId(), "corr-1"));

        assertTrue(adjudicationRepository.findByIdAndOrganizationId(saved.getId(), north.getId()).isPresent(),
                "same-tenant lookup finds the row");
        assertTrue(adjudicationRepository.findByIdAndOrganizationId(saved.getId(), green.getId()).isEmpty(),
                "cross-tenant lookup of the same id returns nothing (→ secure 404)");
        assertTrue(adjudicationRepository.findByOrganizationIdAndClaimId(north.getId(), claim.getId()).isPresent(),
                "the by-claim finder returns the stored adjudication");
        assertTrue(adjudicationRepository.existsByOrganizationIdAndClaimId(north.getId(), claim.getId()));
    }

    @Test
    void lines_read_back_in_line_order() {
        Organization org = organizationRepository.save(new Organization("Adj Lines Org (adj-test)"));
        AppUser actor = appUserRepository.save(new AppUser("adjl-" + UUID.randomUUID() + "@ex.org", "Reviewer"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-7101", "Patient", LocalDate.of(1990, 1, 1)));
        Claim claim = claimRepository.save(new Claim(
                org.getId(), patient.getId(), "CLM-ADJ0002", LocalDate.of(2026, 1, 10),
                new BigDecimal("195.50"), actor.getId()));
        Adjudication adj = adjudicationRepository.save(new Adjudication(
                org.getId(), claim.getId(), 1, AdjudicationOutcome.ADJUDICATED, null, null,
                new BigDecimal("195.50"), new BigDecimal("195.50"), new BigDecimal("0.00"),
                new BigDecimal("195.50"), actor.getId(), "corr-2"));

        // Save line 2 before line 1 to prove the finder orders by line number, not insertion.
        adjudicationLineRepository.save(line(org.getId(), adj.getId(), UUID.randomUUID(), 2, "80053"));
        adjudicationLineRepository.save(line(org.getId(), adj.getId(), UUID.randomUUID(), 1, "99213"));

        List<AdjudicationLine> lines = adjudicationLineRepository
                .findByOrganizationIdAndAdjudicationIdOrderByLineNumberAsc(org.getId(), adj.getId());
        assertEquals(2, lines.size());
        assertEquals(1, lines.get(0).getLineNumber());
        assertEquals(2, lines.get(1).getLineNumber());
    }

    private AdjudicationLine line(UUID orgId, UUID adjId, UUID claimLineId, int lineNumber, String code) {
        BigDecimal zero = new BigDecimal("0.00");
        BigDecimal charge = new BigDecimal("100.00");
        return new AdjudicationLine(orgId, adjId, claimLineId, lineNumber, CodeSystem.CPT, code,
                LineOutcome.COVERED, charge, charge, zero, charge, zero, zero, zero, charge);
    }
}
