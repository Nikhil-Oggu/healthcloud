package com.healthcloud.patient;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.relationship.ProviderPatientAssignment;
import com.healthcloud.relationship.ProviderPatientAssignmentRepository;
import com.healthcloud.relationship.ProviderPatientAssignmentStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The single choke point for "may this caller reach this patient at all?" — the object/relationship access
 * layer (source-of-truth §21 layer 6, §14.3). Every endpoint that exposes a patient or something nested
 * under one (the patient read, consent directives, provider assignments) resolves access through here, so the
 * gate cannot be side-stepped by a nested route.
 *
 * <p>The rule: a request is tenant-scoped first (a patient in another tenant is a secure 404), then a
 * <b>PROVIDER without a broad coordinator/admin role</b> may read only patients they are <b>actively
 * assigned</b> to — an unassigned patient is a secure 404 (§21.5), never a 403 that would confirm the patient
 * exists. Coordinators/admins keep broad tenant access. This layer only ever <i>narrows</i> access; consent +
 * field-level masking (§22.5, §23) are separate, independent layers applied after it.
 *
 * <p>This guard owns the "actively assigned" query so the whole access decision has one implementation and no
 * bean cycle (it depends only on repositories, not on the services it protects).
 */
@Component
public class PatientAccessGuard {

    /** Roles with broad (coordination/admin) patient access — not gated by a provider-patient relationship. */
    private static final Set<String> BROAD_READ_ROLES = Set.of("CARE_COORDINATOR", "ORG_ADMIN");

    private static final String PROVIDER_ROLE = "PROVIDER";

    private static final String PATIENT_ROLE = "PATIENT";

    private final PatientRepository patients;
    private final ProviderPatientAssignmentRepository assignments;
    private final UserContextAccessor userContext;

    public PatientAccessGuard(PatientRepository patients,
                              ProviderPatientAssignmentRepository assignments,
                              UserContextAccessor userContext) {
        this.patients = patients;
        this.assignments = assignments;
        this.userContext = userContext;
    }

    /**
     * Confirm the patient exists in the caller's tenant AND the caller may reach it (§21 layer 6), else a
     * secure 404. Returns the loaded {@link Patient} so callers need not re-query it.
     */
    public Patient requireAccessibleInTenant(UUID patientId) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        Patient patient = patients.findByIdAndOrganizationId(patientId, organizationId)
                .orElseThrow(NotFoundException::new);
        if (isProviderGated(caller)
                && !isActivelyAssigned(organizationId, caller.userId(), patientId)) {
            throw new NotFoundException();
        }
        if (isPatientSelfGated(caller) && !caller.userId().equals(patient.getAppUserId())) {
            throw new NotFoundException();
        }
        return patient;
    }

    /**
     * The patient ids a <b>gated</b> caller may reach, or {@link Optional#empty()} when the caller has broad
     * tenant access (coordinator/admin, and — until the permission matrix lands — claims reviewer). The single
     * source of truth for scoping list reads: a PROVIDER → their actively-assigned patients; a PATIENT → the one
     * profile linked to them (possibly none). Both {@code PatientService.list} and {@code ServiceRequestService.list}
     * route through here so a gated caller sees only their own patients' rows.
     */
    public Optional<Set<UUID>> accessiblePatientIdsIfGated(UserContext caller, UUID organizationId) {
        if (isProviderGated(caller)) {
            return Optional.of(activePatientIdsFor(organizationId, caller.userId()));
        }
        if (isPatientSelfGated(caller)) {
            return Optional.of(selfPatientIds(organizationId, caller.userId()));
        }
        return Optional.empty();
    }

    /**
     * Whether the caller's patient access is gated by a provider-patient relationship: a PROVIDER who does not
     * also hold a broad (coordinator/admin) role.
     */
    public boolean isProviderGated(UserContext caller) {
        return !isBroad(caller) && caller.roles().contains(PROVIDER_ROLE);
    }

    /**
     * Whether the caller is gated to their own patient profile: a PATIENT who is neither a broad role nor a
     * provider. Such a caller may reach only the patient row whose {@code app_user_id} is their user id.
     */
    public boolean isPatientSelfGated(UserContext caller) {
        return !isBroad(caller) && !caller.roles().contains(PROVIDER_ROLE)
                && caller.roles().contains(PATIENT_ROLE);
    }

    private boolean isBroad(UserContext caller) {
        for (String role : BROAD_READ_ROLES) {
            if (caller.roles().contains(role)) {
                return true;
            }
        }
        return false;
    }

    /** The (at most one) patient profile linked to this login within the tenant. */
    private Set<UUID> selfPatientIds(UUID organizationId, UUID userId) {
        return patients.findByOrganizationIdAndAppUserId(organizationId, userId)
                .map(p -> Set.of(p.getId()))
                .orElseGet(Set::of);
    }

    /**
     * The patient ids a provider is ACTIVELY assigned to (in force today) within a tenant — the input to the
     * relationship gate. Re-checks the effective-date window, so a PENDING or past-its-window row does not
     * grant access.
     */
    public Set<UUID> activePatientIdsFor(UUID organizationId, UUID providerUserId) {
        LocalDate today = LocalDate.now();
        return assignments
                .findByOrganizationIdAndProviderUserIdAndStatus(
                        organizationId, providerUserId, ProviderPatientAssignmentStatus.ACTIVE)
                .stream()
                .filter(a -> inForce(a, today))
                .map(ProviderPatientAssignment::getPatientId)
                .collect(Collectors.toSet());
    }

    /** Whether a provider has an in-force ACTIVE assignment to a patient (the per-patient gate check). */
    public boolean isActivelyAssigned(UUID organizationId, UUID providerUserId, UUID patientId) {
        return activePatientIdsFor(organizationId, providerUserId).contains(patientId);
    }

    /** Whether {@code today} falls within the assignment's effective window (open-ended when no end date). */
    private static boolean inForce(ProviderPatientAssignment a, LocalDate today) {
        boolean started = !today.isBefore(a.getEffectiveFrom());
        boolean notEnded = a.getEffectiveTo() == null || !today.isAfter(a.getEffectiveTo());
        return started && notEnded;
    }
}
