package com.healthcloud.claimreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paged-finder coverage for the manual-review aggregate (§Phase 9 slice 4): {@code searchAll},
 * {@code searchForPatients} and {@code searchForClaim} count, sort, filter by status and scope correctly, in the
 * database — and are tenant-scoped. Runs without the {@code local} profile so the seeder does not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class ClaimReviewRepositoryTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired ClaimRepository claimRepository;
    @Autowired ClaimReviewRepository reviewRepository;

    private Claim claim(Organization org, Patient patient, AppUser author, String number) {
        return claimRepository.save(new Claim(
                org.getId(), patient.getId(), number, LocalDate.now().minusWeeks(2),
                new BigDecimal("150.00"), author.getId()));
    }

    @Test
    void searchAll_pages_counts_and_filters_by_status_in_the_database() {
        Organization org = organizationRepository.save(new Organization("Mrv Search Org"));
        AppUser author = appUserRepository.save(new AppUser("mrvs-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-9601", "Patient", LocalDate.of(1990, 1, 1)));
        Claim c1 = claim(org, patient, author, "CLM-MS1");
        Claim c2 = claim(org, patient, author, "CLM-MS2");
        Claim c3 = claim(org, patient, author, "CLM-MS3");

        savedReview(org, c1, patient, author, "MRV-S1", ClaimReviewStatus.OPEN);
        savedReview(org, c2, patient, author, "MRV-S2", ClaimReviewStatus.OPEN);
        savedReview(org, c3, patient, author, "MRV-S3", ClaimReviewStatus.RESOLVED);

        Page<ClaimReview> firstPage = reviewRepository.searchAll(
                org.getId(), null, null, PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "reviewNumber")));
        assertEquals(3, firstPage.getTotalElements(), "the count spans every matching row, not just the page");
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(List.of("MRV-S1", "MRV-S2"),
                firstPage.getContent().stream().map(ClaimReview::getReviewNumber).toList());

        Page<ClaimReview> resolved = reviewRepository.searchAll(
                org.getId(), ClaimReviewStatus.RESOLVED, null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(1, resolved.getTotalElements());
        assertEquals("MRV-S3", resolved.getContent().get(0).getReviewNumber());
    }

    @Test
    void searchAll_filters_by_a_free_text_review_number_case_insensitively() {
        Organization org = organizationRepository.save(new Organization("Mrv FreeText Org"));
        AppUser author = appUserRepository.save(new AppUser("mrvft-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient patient = patientRepository.save(
                new Patient(org.getId(), "NC-9801", "Patient", LocalDate.of(1990, 1, 1)));
        Claim c1 = claim(org, patient, author, "CLM-MFT1");
        Claim c2 = claim(org, patient, author, "CLM-MFT2");
        Claim c3 = claim(org, patient, author, "CLM-MFT3");

        savedReview(org, c1, patient, author, "MRV-ALPHA1", ClaimReviewStatus.OPEN);
        savedReview(org, c2, patient, author, "MRV-ALPHA2", ClaimReviewStatus.OPEN);
        savedReview(org, c3, patient, author, "MRV-BETA1", ClaimReviewStatus.OPEN);

        Page<ClaimReview> alphas = reviewRepository.searchAll(
                org.getId(), null, "%alpha%", PageRequest.of(0, 10, Sort.by("reviewNumber")));
        assertEquals(2, alphas.getTotalElements(), "both ALPHA reviews match (lowercase term, uppercase numbers)");
        assertEquals(List.of("MRV-ALPHA1", "MRV-ALPHA2"),
                alphas.getContent().stream().map(ClaimReview::getReviewNumber).toList());
        assertEquals(0, reviewRepository.searchAll(
                org.getId(), null, "%zzz%", PageRequest.of(0, 10, Sort.by("reviewNumber"))).getTotalElements(),
                "a non-matching term returns nothing");
        assertEquals(3, reviewRepository.searchAll(
                org.getId(), null, null, PageRequest.of(0, 10, Sort.by("reviewNumber"))).getTotalElements(),
                "a null term disables the search clause");
    }

    @Test
    void searchForPatients_and_searchForClaim_scope_correctly() {
        Organization org = organizationRepository.save(new Organization("Mrv Scope Org"));
        AppUser author = appUserRepository.save(new AppUser("mrvsc-" + UUID.randomUUID() + "@ex.org", "Author"));
        Patient p1 = patientRepository.save(new Patient(org.getId(), "NC-9701", "P1", LocalDate.of(1990, 1, 1)));
        Patient p2 = patientRepository.save(new Patient(org.getId(), "NC-9702", "P2", LocalDate.of(1990, 1, 1)));
        Claim c1 = claim(org, p1, author, "CLM-MSP1");
        Claim c2 = claim(org, p1, author, "CLM-MSP2");
        Claim c3 = claim(org, p2, author, "CLM-MSP3");
        savedReview(org, c1, p1, author, "MRV-SP1", ClaimReviewStatus.OPEN);
        savedReview(org, c2, p1, author, "MRV-SP2", ClaimReviewStatus.OPEN);
        savedReview(org, c3, p2, author, "MRV-SP3", ClaimReviewStatus.OPEN);

        Page<ClaimReview> onlyP1 = reviewRepository.searchForPatients(
                org.getId(), Set.of(p1.getId()), null, null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(2, onlyP1.getTotalElements(), "only the requested patients' reviews are visible");

        Page<ClaimReview> onlyC1 = reviewRepository.searchForClaim(
                org.getId(), c1.getId(), null, null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(1, onlyC1.getTotalElements(), "the claim filter narrows to that claim's reviews");
        assertEquals("MRV-SP1", onlyC1.getContent().get(0).getReviewNumber());

        Organization other = organizationRepository.save(new Organization("Mrv Other Org"));
        Page<ClaimReview> crossTenant = reviewRepository.searchForClaim(
                other.getId(), c1.getId(), null, null, PageRequest.of(0, 10, Sort.by("createdAt")));
        assertEquals(0, crossTenant.getTotalElements());
    }

    /** Save a review with an explicit status (default on creation is OPEN). */
    private ClaimReview savedReview(Organization org, Claim claim, Patient patient, AppUser author,
                                    String number, ClaimReviewStatus status) {
        ClaimReview review = new ClaimReview(
                org.getId(), claim.getId(), patient.getId(), number, "Flagged", author.getId());
        review.setStatus(status);
        return reviewRepository.saveAndFlush(review);
    }
}
