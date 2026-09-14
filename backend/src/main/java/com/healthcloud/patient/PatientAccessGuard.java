package com.healthcloud.patient;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.relationship.ProviderPatientAssignment;
import com.healthcloud.relationship.ProviderPatientAssignmentRepository;
import com.healthcloud.relationship.ProviderPatientAssignmentStatus;
import java.time.LocalDate;
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
        return patient;
    }

    /**
     * Whether the caller's patient access is gated by a provider-patient relationship: a PROVIDER who does not
     * also hold a broad (coordinator/admin) role. Other roles are not relationship-gated in this slice.
     */
    public boolean isProviderGated(UserContext caller) {
        for (String role : BROAD_READ_ROLES) {
            if (caller.roles().contains(role)) {
                return false;
            }
        }
        return caller.roles().contains(PROVIDER_ROLE);
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
