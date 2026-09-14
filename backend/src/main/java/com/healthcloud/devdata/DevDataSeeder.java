package com.healthcloud.devdata;

import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.identity.AppUserStatus;
import com.healthcloud.identity.OrganizationMembership;
import com.healthcloud.identity.OrganizationMembershipRepository;
import com.healthcloud.identity.Role;
import com.healthcloud.identity.RoleRepository;
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
import com.healthcloud.relationship.CareCoordinatorAssignment;
import com.healthcloud.relationship.CareCoordinatorAssignmentRepository;
import com.healthcloud.relationship.CareCoordinatorAssignmentStatus;
import com.healthcloud.relationship.ProviderPatientAssignment;
import com.healthcloud.relationship.ProviderPatientAssignmentRepository;
import com.healthcloud.relationship.ProviderPatientAssignmentStatus;
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

    public DevDataSeeder(OrganizationRepository organizationRepository,
                         AppUserRepository appUserRepository,
                         RoleRepository roleRepository,
                         OrganizationMembershipRepository membershipRepository,
                         UserRoleRepository userRoleRepository,
                         FacilityRepository facilityRepository,
                         FacilityMembershipRepository facilityMembershipRepository,
                         PatientRepository patientRepository,
                         ProviderPatientAssignmentRepository providerPatientAssignmentRepository,
                         CareCoordinatorAssignmentRepository careCoordinatorAssignmentRepository) {
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
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (organizationRepository.findByName(NORTHCARE).isPresent()) {
            log.info("Demo data already present; skipping seed.");
            return;
        }
        log.info("Seeding synthetic demo data ({} and {})...", NORTHCARE, GREEN_VALLEY);
        seedOrganization(NORTHCARE, "NorthCare Main Clinic", "northcare.example.org", "NC");
        seedOrganization(GREEN_VALLEY, "Green Valley Family Center", "greenvalley.example.org", "GV");
        log.info("Demo data seeded.");
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
