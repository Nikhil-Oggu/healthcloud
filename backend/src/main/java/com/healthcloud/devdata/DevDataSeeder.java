package com.healthcloud.devdata;

import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.identity.AppUserStatus;
import com.healthcloud.identity.OrganizationMembership;
import com.healthcloud.identity.OrganizationMembershipRepository;
import com.healthcloud.identity.Role;
import com.healthcloud.identity.RoleRepository;
import com.healthcloud.claim.Claim;
import com.healthcloud.claim.ClaimLine;
import com.healthcloud.claim.ClaimLineRepository;
import com.healthcloud.claim.ClaimRepository;
import com.healthcloud.claim.ClaimStatus;
import com.healthcloud.claim.ClaimStatusHistory;
import com.healthcloud.claim.ClaimStatusHistoryRepository;
import com.healthcloud.clinical.ClinicalSummary;
import com.healthcloud.clinical.ClinicalSummaryRepository;
import com.healthcloud.clinical.ClinicalSummaryType;
import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.coverage.CoveragePlan;
import com.healthcloud.coverage.CoveragePlanRepository;
import com.healthcloud.coverage.PatientEligibility;
import com.healthcloud.coverage.PatientEligibilityRepository;
import com.healthcloud.coverage.PlanFeeScheduleEntry;
import com.healthcloud.coverage.PlanFeeScheduleRepository;
import com.healthcloud.coverage.PlanPriorAuthRequirement;
import com.healthcloud.coverage.PlanPriorAuthRequirementRepository;
import com.healthcloud.coverage.PlanType;
import com.healthcloud.identity.UserRole;
import com.healthcloud.identity.UserRoleRepository;
import com.healthcloud.organization.Facility;
import com.healthcloud.organization.FacilityMembership;
import com.healthcloud.organization.FacilityMembershipRepository;
import com.healthcloud.organization.FacilityRepository;
import com.healthcloud.organization.Organization;
import com.healthcloud.organization.OrganizationRepository;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientRepository;
import com.healthcloud.priorauth.PriorAuthorization;
import com.healthcloud.priorauth.PriorAuthorizationRepository;
import com.healthcloud.priorauth.PriorAuthorizationStatus;
import com.healthcloud.priorauth.PriorAuthorizationStatusHistory;
import com.healthcloud.priorauth.PriorAuthorizationStatusHistoryRepository;
import com.healthcloud.referral.Referral;
import com.healthcloud.referral.ReferralRepository;
import com.healthcloud.referral.ReferralStatus;
import com.healthcloud.referral.ReferralStatusHistory;
import com.healthcloud.referral.ReferralStatusHistoryRepository;
import com.healthcloud.relationship.CareCoordinatorAssignment;
import com.healthcloud.relationship.CareCoordinatorAssignmentRepository;
import com.healthcloud.relationship.CareCoordinatorAssignmentStatus;
import com.healthcloud.relationship.ProviderPatientAssignment;
import com.healthcloud.relationship.ProviderPatientAssignmentRepository;
import com.healthcloud.relationship.ProviderPatientAssignmentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the deterministic synthetic demo scenario (NorthCare + Green Valley) on startup.
 * Active ONLY under the {@code local} profile so it never runs in staging/production.
 * Idempotent: if NorthCare already exists it does nothing. All data is SYNTHETIC.
 */
@Component
@Profile("local")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String NORTHCARE = "NorthCare Health";
    private static final String GREEN_VALLEY = "Green Valley Clinic";

    private final OrganizationRepository organizationRepository;
    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final UserRoleRepository userRoleRepository;
    private final FacilityRepository facilityRepository;
    private final FacilityMembershipRepository facilityMembershipRepository;
    private final PatientRepository patientRepository;
    private final ProviderPatientAssignmentRepository providerPatientAssignmentRepository;
    private final CareCoordinatorAssignmentRepository careCoordinatorAssignmentRepository;
    private final MedicalCodeRepository medicalCodeRepository;
    private final ClinicalSummaryRepository clinicalSummaryRepository;
    private final ClaimRepository claimRepository;
    private final ClaimLineRepository claimLineRepository;
    private final ClaimStatusHistoryRepository claimStatusHistoryRepository;
    private final CoveragePlanRepository coveragePlanRepository;
    private final PatientEligibilityRepository patientEligibilityRepository;
    private final PlanFeeScheduleRepository planFeeScheduleRepository;
    private final PlanPriorAuthRequirementRepository planPriorAuthRequirementRepository;
    private final PriorAuthorizationRepository priorAuthorizationRepository;
    private final PriorAuthorizationStatusHistoryRepository priorAuthorizationStatusHistoryRepository;
    private final ReferralRepository referralRepository;
    private final ReferralStatusHistoryRepository referralStatusHistoryRepository;

    public DevDataSeeder(OrganizationRepository organizationRepository,
                         AppUserRepository appUserRepository,
                         RoleRepository roleRepository,
                         OrganizationMembershipRepository membershipRepository,
                         UserRoleRepository userRoleRepository,
                         FacilityRepository facilityRepository,
                         FacilityMembershipRepository facilityMembershipRepository,
                         PatientRepository patientRepository,
                         ProviderPatientAssignmentRepository providerPatientAssignmentRepository,
                         CareCoordinatorAssignmentRepository careCoordinatorAssignmentRepository,
                         MedicalCodeRepository medicalCodeRepository,
                         ClinicalSummaryRepository clinicalSummaryRepository,
                         ClaimRepository claimRepository,
                         ClaimLineRepository claimLineRepository,
                         ClaimStatusHistoryRepository claimStatusHistoryRepository,
                         CoveragePlanRepository coveragePlanRepository,
                         PatientEligibilityRepository patientEligibilityRepository,
                         PlanFeeScheduleRepository planFeeScheduleRepository,
                         PlanPriorAuthRequirementRepository planPriorAuthRequirementRepository,
                         PriorAuthorizationRepository priorAuthorizationRepository,
                         PriorAuthorizationStatusHistoryRepository priorAuthorizationStatusHistoryRepository,
                         ReferralRepository referralRepository,
                         ReferralStatusHistoryRepository referralStatusHistoryRepository) {
        this.organizationRepository = organizationRepository;
        this.appUserRepository = appUserRepository;
        this.roleRepository = roleRepository;
        this.membershipRepository = membershipRepository;
        this.userRoleRepository = userRoleRepository;
        this.facilityRepository = facilityRepository;
        this.facilityMembershipRepository = facilityMembershipRepository;
        this.patientRepository = patientRepository;
        this.providerPatientAssignmentRepository = providerPatientAssignmentRepository;
        this.careCoordinatorAssignmentRepository = careCoordinatorAssignmentRepository;
        this.medicalCodeRepository = medicalCodeRepository;
        this.clinicalSummaryRepository = clinicalSummaryRepository;
        this.claimRepository = claimRepository;
        this.claimLineRepository = claimLineRepository;
        this.claimStatusHistoryRepository = claimStatusHistoryRepository;
        this.coveragePlanRepository = coveragePlanRepository;
        this.patientEligibilityRepository = patientEligibilityRepository;
        this.planFeeScheduleRepository = planFeeScheduleRepository;
        this.planPriorAuthRequirementRepository = planPriorAuthRequirementRepository;
        this.priorAuthorizationRepository = priorAuthorizationRepository;
        this.priorAuthorizationStatusHistoryRepository = priorAuthorizationStatusHistoryRepository;
        this.referralRepository = referralRepository;
        this.referralStatusHistoryRepository = referralStatusHistoryRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (organizationRepository.findByName(NORTHCARE).isPresent()) {
            log.info("Demo data already present; skipping seed.");
            return;
        }
        log.info("Seeding synthetic demo data ({} and {})...", NORTHCARE, GREEN_VALLEY);
        // Global reference data first: clinical summaries FK their diagnosis to the medical code catalog.
        seedMedicalCodes();
        seedOrganization(NORTHCARE, "NorthCare Main Clinic", "northcare.example.org", "NC");
        seedOrganization(GREEN_VALLEY, "Green Valley Family Center", "greenvalley.example.org", "GV");
        log.info("Demo data seeded.");
    }

    /**
     * Seed a small illustrative slice of the medical code catalog (Phase 4). GLOBAL, not per-tenant: these are
     * public national code standards shared by every organization. Real-format ICD-10-CM diagnoses and
     * HCPCS/CPT procedures — public reference vocabularies, not PHI, so they don't fall under the
     * synthetic-only patient-data rule. Later slices attach these to clinical summaries and claim lines.
     */
    private void seedMedicalCodes() {
        List<MedicalCode> catalog = List.of(
                // ICD-10-CM diagnoses
                new MedicalCode(CodeSystem.ICD10CM, "E11.9", "Type 2 diabetes mellitus without complications"),
                new MedicalCode(CodeSystem.ICD10CM, "I10", "Essential (primary) hypertension"),
                new MedicalCode(CodeSystem.ICD10CM, "J45.909", "Unspecified asthma, uncomplicated"),
                new MedicalCode(CodeSystem.ICD10CM, "M54.5", "Low back pain"),
                new MedicalCode(CodeSystem.ICD10CM, "J06.9", "Acute upper respiratory infection, unspecified"),
                new MedicalCode(CodeSystem.ICD10CM, "E78.5", "Hyperlipidemia, unspecified"),
                new MedicalCode(CodeSystem.ICD10CM, "F41.1", "Generalized anxiety disorder"),
                new MedicalCode(CodeSystem.ICD10CM, "Z00.00",
                        "Encounter for general adult medical exam without abnormal findings"),
                // CPT procedures / services
                new MedicalCode(CodeSystem.CPT, "99213",
                        "Office/outpatient visit, established patient, low complexity"),
                new MedicalCode(CodeSystem.CPT, "99214",
                        "Office/outpatient visit, established patient, moderate complexity"),
                new MedicalCode(CodeSystem.CPT, "99203",
                        "Office/outpatient visit, new patient, low complexity"),
                new MedicalCode(CodeSystem.CPT, "80053", "Comprehensive metabolic panel"),
                new MedicalCode(CodeSystem.CPT, "85025",
                        "Complete blood count (CBC) with differential white blood cell count"),
                new MedicalCode(CodeSystem.CPT, "93000", "Electrocardiogram, routine, with interpretation"),
                // HCPCS Level II
                new MedicalCode(CodeSystem.HCPCS, "J1815", "Injection, insulin, per 5 units"),
                new MedicalCode(CodeSystem.HCPCS, "G0439",
                        "Annual wellness visit, personalized prevention plan, subsequent visit"),
                new MedicalCode(CodeSystem.HCPCS, "A4253", "Blood glucose test strips, per 50 strips"));
        medicalCodeRepository.saveAll(catalog);
    }

    private void seedOrganization(String orgName, String facilityName, String emailDomain, String mrnPrefix) {
        Organization org = organizationRepository.save(new Organization(orgName));
        Facility facility = facilityRepository.save(
                new Facility(org, facilityName, "CLINIC", "123 Synthetic St"));

        // The patient login is patient Sam Sample's own portal account (linked below), so its display name
        // matches the profile it represents — a coherent patient self-service demo (§12.1, §21).
        AppUser patientUser =
                createMember(org, facility, "patient",     "Sam Sample",        "PATIENT",          emailDomain, false);
        AppUser provider =
                createMember(org, facility, "provider",    "Dana Provider",     "PROVIDER",         emailDomain, true);
        AppUser coordinator =
                createMember(org, facility, "coordinator", "Cory Coordinator",  "CARE_COORDINATOR", emailDomain, true);
        createMember(org, facility, "reviewer",    "Riley Reviewer",    "CLAIMS_REVIEWER",  emailDomain, false);
        AppUser admin =
                createMember(org, facility, "admin",       "Alex Admin",        "ORG_ADMIN",        emailDomain, false);

        List<Patient> patients = seedPatients(org, mrnPrefix);

        // Link the patient login to their own profile (Sam Sample, index 0) so a PATIENT user sees only their
        // own record + requests + consent (the object/relationship gate applied to patient self-service, §21).
        Patient self = patients.get(0);
        self.setAppUserId(patientUser.getId());
        patientRepository.save(self);

        // Baseline care relationships (§14.3): assign the provider to the first two patients (the third is
        // left unassigned) so the object/relationship gate is demonstrable — the provider sees 2 of 3.
        assignProvider(org, patients.get(0), provider, coordinator);
        assignProvider(org, patients.get(1), provider, coordinator);

        // Care-team coordinator relationships (§14.3, §22): put the coordinator on the care team of the first
        // and third patients, so the CARE_TEAM consent tier has demonstrable data next slice (varied overlap
        // with the provider assignments above).
        assignCoordinator(org, patients.get(0), coordinator, admin);
        assignCoordinator(org, patients.get(2), coordinator, admin);

        // A little clinical context (Phase 4) for the two patients the provider is assigned to, so a demo read
        // returns something. The narrative is consent-controlled (§23) — with no consent directive seeded, a
        // read masks it by default; recording a CLINICAL_CONTEXT grant on the patient makes it visible.
        seedClinicalSummaries(org, patients.get(0), provider);
        seedClinicalSummaries(org, patients.get(1), provider);

        // A sample DRAFT claim (Phase 4) for the first patient, with two procedure lines from the catalog, so a
        // demo claims list/queue returns something. Amounts are synthetic.
        seedClaim(org, patients.get(0), provider);

        // A couple of synthetic coverage plans (Phase 4) the org administers, so eligibility and Phase-5
        // adjudication have benefit parameters to reference; then enroll the first patient in the PPO.
        CoveragePlan ppo = seedCoveragePlans(org, mrnPrefix);
        seedEligibility(org, patients.get(0), ppo, mrnPrefix, coordinator);
        seedFeeSchedule(org, ppo, admin);

        // A sample REQUESTED prior authorization (Phase 6) for the first patient under the PPO, so a demo
        // prior-auth queue returns something a reviewer can approve/deny. Synthetic; coded data only.
        seedPriorAuth(org, patients.get(0), ppo, provider);

        // Mark a procedure (99214) as requiring prior auth under the PPO, so a demo claim for it adjudicates
        // AUTH_REQUIRED until an authorization is approved. Deliberately NOT 99213/80053 (the seeded claim +
        // the accumulator/fee-schedule tests assert exact amounts for those on the seeded PPO).
        seedPriorAuthRequirement(org, ppo, admin);

        // A sample REQUESTED referral (Phase 6) for the first patient to Cardiology, so a demo referral queue
        // returns something a coordinator can approve/deny. Synthetic; coded reason only (no narrative).
        seedReferral(org, patients.get(0), provider);
    }

    /**
     * A synthetic REQUESTED referral to Cardiology for a coded reason (I10 hypertension), with its creation
     * history row (null → REQUESTED), consistent with the referral state machine (§31.6).
     */
    private void seedReferral(Organization org, Patient patient, AppUser requestedBy) {
        Referral referral = referralRepository.save(new Referral(
                org.getId(), patient.getId(),
                "REF-" + org.getId().toString().substring(0, 4).toUpperCase() + "01",
                "Cardiology", CodeSystem.ICD10CM.name(), "I10", requestedBy.getId()));
        referralStatusHistoryRepository.save(new ReferralStatusHistory(
                org.getId(), referral.getId(), null, ReferralStatus.REQUESTED, requestedBy.getId(),
                "Referral requested", null));
    }

    /** A synthetic prior-auth requirement: the PPO requires prior authorization for a moderate office visit. */
    private void seedPriorAuthRequirement(Organization org, CoveragePlan plan, AppUser createdBy) {
        planPriorAuthRequirementRepository.save(new PlanPriorAuthRequirement(
                org.getId(), plan.getId(), CodeSystem.CPT, "99214", createdBy.getId()));
    }

    /**
     * A synthetic REQUESTED prior authorization for a planned procedure (99213) under the PPO, with its
     * creation history row (null → REQUESTED), consistent with the prior-auth state machine (§31.6).
     */
    private void seedPriorAuth(Organization org, Patient patient, CoveragePlan plan, AppUser requestedBy) {
        PriorAuthorization auth = priorAuthorizationRepository.save(new PriorAuthorization(
                org.getId(), patient.getId(),
                "PA-" + org.getId().toString().substring(0, 4).toUpperCase() + "01",
                plan.getId(), CodeSystem.CPT.name(), "99213",
                LocalDate.now().plusWeeks(2), LocalDate.now().plusWeeks(6), requestedBy.getId()));
        priorAuthorizationStatusHistoryRepository.save(new PriorAuthorizationStatusHistory(
                org.getId(), auth.getId(), null, PriorAuthorizationStatus.REQUESTED, requestedBy.getId(),
                "Prior authorization requested", null));
    }

    /**
     * A synthetic fee-schedule entry on the PPO so a demo adjudication shows a real allowed amount below the
     * charge: the metabolic panel (80053) is billed $45.50 on the seeded claim but the plan allows only $40.00
     * (the $5.50 difference is a provider write-off). The office-visit line (99213) has no entry, so it falls
     * back to allowed = charge — the two lines together show both paths in one adjudication.
     */
    private void seedFeeSchedule(Organization org, CoveragePlan plan, AppUser createdBy) {
        planFeeScheduleRepository.save(new PlanFeeScheduleEntry(
                org.getId(), plan.getId(), CodeSystem.CPT, "80053", new BigDecimal("40.00"), createdBy.getId()));
    }

    /**
     * Two synthetic benefit plans per tenant: a standard PPO and a high-deductible plan. All amounts synthetic.
     * Returns the PPO so a patient can be enrolled in it.
     */
    private CoveragePlan seedCoveragePlans(Organization org, String planPrefix) {
        CoveragePlan ppo = coveragePlanRepository.save(new CoveragePlan(
                org.getId(), planPrefix + "-PPO-STD", "Standard PPO", PlanType.PPO,
                new BigDecimal("1500.00"), new BigDecimal("0.2000"), new BigDecimal("25.00"),
                new BigDecimal("6000.00")));
        coveragePlanRepository.save(new CoveragePlan(
                org.getId(), planPrefix + "-HDHP", "High-Deductible Health Plan", PlanType.HDHP,
                new BigDecimal("4000.00"), new BigDecimal("0.1000"), new BigDecimal("0.00"),
                new BigDecimal("8000.00")));
        return ppo;
    }

    /** Enroll a patient in a plan, open-ended from a year ago, so a demo/adjudication has coverage to find. */
    private void seedEligibility(Organization org, Patient patient, CoveragePlan plan, String memberPrefix,
                                AppUser enrolledBy) {
        patientEligibilityRepository.save(new PatientEligibility(
                org.getId(), patient.getId(), plan.getId(), memberPrefix + "-M0001",
                LocalDate.now().minusYears(1), null, enrolledBy.getId()));
    }

    /** A synthetic DRAFT claim with two procedure lines (CPT), header total = sum of the line charges. */
    private void seedClaim(Organization org, Patient patient, AppUser author) {
        BigDecimal officeVisit = new BigDecimal("150.00");
        BigDecimal metabolicPanel = new BigDecimal("45.50");
        Claim claim = claimRepository.save(new Claim(
                org.getId(), patient.getId(),
                "CLM-" + org.getId().toString().substring(0, 4).toUpperCase() + "01",
                LocalDate.now().minusWeeks(3), officeVisit.add(metabolicPanel), author.getId()));
        claimLineRepository.save(new ClaimLine(
                org.getId(), claim.getId(), 1, CodeSystem.CPT, "99213", 1, officeVisit));
        claimLineRepository.save(new ClaimLine(
                org.getId(), claim.getId(), 2, CodeSystem.CPT, "80053", 1, metabolicPanel));
        // The creation history row (null → DRAFT), consistent with the claim state machine (§31.6).
        claimStatusHistoryRepository.save(new ClaimStatusHistory(
                org.getId(), claim.getId(), null, ClaimStatus.DRAFT, author.getId(), "Claim created", null));
    }

    /** A couple of synthetic clinical summaries for a patient, each pointing at a real ICD-10-CM diagnosis. */
    private void seedClinicalSummaries(Organization org, Patient patient, AppUser author) {
        clinicalSummaryRepository.save(new ClinicalSummary(
                org.getId(), patient.getId(), ClinicalSummaryType.ENCOUNTER,
                LocalDate.now().minusMonths(2), "Routine follow-up visit",
                CodeSystem.ICD10CM, "E11.9",
                "Established patient seen for diabetes management; labs reviewed, medication continued.",
                author.getId()));
        clinicalSummaryRepository.save(new ClinicalSummary(
                org.getId(), patient.getId(), ClinicalSummaryType.DIAGNOSIS,
                LocalDate.now().minusWeeks(3), "Elevated blood pressure",
                CodeSystem.ICD10CM, "I10",
                "Blood pressure elevated on repeat readings; lifestyle counseling provided.",
                author.getId()));
    }

    /** A few clearly-synthetic patient profiles per tenant (Phase 2). MRNs are unique within the org. */
    private List<Patient> seedPatients(Organization org, String mrnPrefix) {
        return List.of(
                patientRepository.save(new Patient(org.getId(), mrnPrefix + "-0001", "Sam Sample",   LocalDate.of(1985, 3, 14))),
                patientRepository.save(new Patient(org.getId(), mrnPrefix + "-0002", "Fern Fixture", LocalDate.of(1992, 11, 2))),
                patientRepository.save(new Patient(org.getId(), mrnPrefix + "-0003", "Mock Muller",  LocalDate.of(1978, 7, 30))));
    }

    /** Record an ACTIVE provider-patient assignment (assigned today, open-ended) by the coordinator. */
    private void assignProvider(Organization org, Patient patient, AppUser provider, AppUser assignedBy) {
        providerPatientAssignmentRepository.save(new ProviderPatientAssignment(
                org.getId(), patient.getId(), provider.getId(), assignedBy.getId(),
                ProviderPatientAssignmentStatus.ACTIVE, LocalDate.now(), null));
    }

    /** Record an ACTIVE care-coordinator assignment (assigned today, open-ended) by the admin. */
    private void assignCoordinator(Organization org, Patient patient, AppUser coordinator, AppUser assignedBy) {
        careCoordinatorAssignmentRepository.save(new CareCoordinatorAssignment(
                org.getId(), patient.getId(), coordinator.getId(), assignedBy.getId(),
                CareCoordinatorAssignmentStatus.ACTIVE, LocalDate.now(), null));
    }

    private AppUser createMember(Organization org, Facility facility, String localPart, String fullName,
                                 String roleCode, String emailDomain, boolean linkToFacility) {
        AppUser user = new AppUser(localPart + "@" + emailDomain, fullName);
        user.setStatus(AppUserStatus.ACTIVE);
        user = appUserRepository.save(user);

        OrganizationMembership membership = membershipRepository.save(new OrganizationMembership(org, user));

        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalStateException("Missing seeded role: " + roleCode));
        userRoleRepository.save(new UserRole(membership, role));

        if (linkToFacility) {
            facilityMembershipRepository.save(new FacilityMembership(facility, membership));
        }
        return user;
    }
}
