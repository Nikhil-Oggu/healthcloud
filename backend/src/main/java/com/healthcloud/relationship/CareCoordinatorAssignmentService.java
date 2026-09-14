package com.healthcloud.relationship;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.identity.MembershipStatus;
import com.healthcloud.identity.OrganizationMembership;
import com.healthcloud.identity.OrganizationMembershipRepository;
import com.healthcloud.identity.UserRole;
import com.healthcloud.identity.UserRoleRepository;
import com.healthcloud.patient.PatientAccessGuard;
import com.healthcloud.patient.PatientRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages care-coordinator↔patient relationships (source-of-truth §14.3, §22). A coordinator/admin assigns a
 * same-tenant CARE_COORDINATOR to a patient; the relationship is effective-dated and auditable (assign/revoke
 * are recorded, never deleted). At most one CURRENT (ACTIVE/PENDING) assignment exists per (patient,
 * coordinator). Mirrors {@link ProviderPatientAssignmentService}.
 *
 * <p>Scope note (this slice): this only RECORDS the relationship. Consuming it — making a CARE_TEAM-scoped
 * consent directive applicable to the patient's care team (providers + coordinators actively assigned) —
 * is the next slice. Coordinators already have broad tenant read access, so recording these changes no
 * existing read behavior.
 */
@Service
@Transactional(readOnly = true)
public class CareCoordinatorAssignmentService {

    /** Roles allowed to manage assignments (§9 coordinator/admin). */
    private static final String[] ASSIGN_ROLES = {"CARE_COORDINATOR", "ORG_ADMIN"};

    /** The role a care-coordinator assignee must hold. */
    private static final String COORDINATOR_ROLE = "CARE_COORDINATOR";

    /** The "current" statuses — in force or scheduled to be (not terminal history). */
    private static final List<CareCoordinatorAssignmentStatus> CURRENT =
            List.of(CareCoordinatorAssignmentStatus.ACTIVE, CareCoordinatorAssignmentStatus.PENDING);

    private final CareCoordinatorAssignmentRepository assignments;
    private final PatientRepository patients;
    private final OrganizationMembershipRepository memberships;
    private final UserRoleRepository userRoles;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public CareCoordinatorAssignmentService(CareCoordinatorAssignmentRepository assignments,
                                            PatientRepository patients,
                                            OrganizationMembershipRepository memberships,
                                            UserRoleRepository userRoles,
                                            PatientAccessGuard accessGuard,
                                            UserContextAccessor userContext) {
        this.assignments = assignments;
        this.patients = patients;
        this.memberships = memberships;
        this.userRoles = userRoles;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * A patient's current (ACTIVE/PENDING) coordinator assignments, oldest first. Reads pass the same
     * object/relationship gate as the patient itself (§21 layer 6): a provider not assigned to the patient
     * gets a secure 404 here too, so this nested endpoint cannot be used to side-step the gate.
     */
    public List<CareCoordinatorAssignmentDto> listCurrent(UUID patientId) {
        UUID organizationId = accessGuard.requireAccessibleInTenant(patientId).getOrganizationId();
        return assignments
                .findByOrganizationIdAndPatientIdAndStatusInOrderByAssignedAtAsc(organizationId, patientId, CURRENT)
                .stream()
                .map(a -> CareCoordinatorAssignmentDto.from(a, nameOf(organizationId, a.getCoordinatorUserId())))
                .toList();
    }

    /**
     * Assign a same-tenant coordinator to a patient. Requires a coordinator/admin role; the patient must be
     * in the caller's tenant (else 404); the target must be a same-tenant CARE_COORDINATOR (else 400 — no
     * existence leak); a duplicate current assignment for the pair is a 409.
     */
    @Transactional
    public CareCoordinatorAssignmentDto assign(UUID patientId, AssignCoordinatorRequest request) {
        userContext.requireAnyRole(ASSIGN_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = requirePatientInTenant(patientId);

        OrganizationMembership coordinator = requireSameTenantCoordinator(organizationId, request.coordinatorUserId());

        LocalDate today = LocalDate.now();
        LocalDate effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : today;
        LocalDate effectiveTo = request.effectiveTo();
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The assignment end date cannot be before its start date.");
        }
        CareCoordinatorAssignmentStatus status = effectiveFrom.isAfter(today)
                ? CareCoordinatorAssignmentStatus.PENDING
                : CareCoordinatorAssignmentStatus.ACTIVE;

        boolean alreadyAssigned = assignments
                .findByOrganizationIdAndPatientIdAndStatusInOrderByAssignedAtAsc(organizationId, patientId, CURRENT)
                .stream()
                .anyMatch(a -> a.getCoordinatorUserId().equals(request.coordinatorUserId()));
        if (alreadyAssigned) {
            throw new ConflictException("That coordinator is already assigned to this patient.");
        }

        CareCoordinatorAssignment saved = assignments.save(new CareCoordinatorAssignment(
                organizationId, patientId, request.coordinatorUserId(), caller.userId(),
                status, effectiveFrom, effectiveTo));
        return CareCoordinatorAssignmentDto.from(saved, coordinator.getAppUser().getFullName());
    }

    /**
     * Revoke a current assignment with immediate effect. Requires a coordinator/admin role; the assignment
     * must belong to this patient in the caller's tenant (else 404), be current (else invalid transition),
     * and the caller's {@code expectedVersion} must match (else 409).
     */
    @Transactional
    public CareCoordinatorAssignmentDto revoke(UUID patientId, UUID assignmentId,
                                               RevokeCoordinatorAssignmentRequest request) {
        userContext.requireAnyRole(ASSIGN_ROLES);
        UUID organizationId = requirePatientInTenant(patientId);

        CareCoordinatorAssignment assignment = assignments.findByIdAndOrganizationId(assignmentId, organizationId)
                .filter(a -> a.getPatientId().equals(patientId))
                .orElseThrow(NotFoundException::new);

        if (!assignment.isCurrent()) {
            throw new InvalidStateTransitionException(
                    "Only a current assignment can be revoked (current status: " + assignment.getStatus() + ").");
        }
        if (assignment.getVersion() != request.expectedVersion()) {
            throw new ConflictException("This assignment was modified by someone else; reload and try again.");
        }

        assignment.revoke();
        return CareCoordinatorAssignmentDto.from(
                assignments.saveAndFlush(assignment), nameOf(organizationId, assignment.getCoordinatorUserId()));
    }

    /** The target must be an active same-tenant CARE_COORDINATOR, else 400 (no existence leak of users). */
    private OrganizationMembership requireSameTenantCoordinator(UUID organizationId, UUID coordinatorUserId) {
        OrganizationMembership membership = memberships
                .findByOrganization_IdAndAppUser_Id(organizationId, coordinatorUserId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "The selected user cannot be assigned to this patient."));
        boolean isCoordinator = userRoles.findByMembership_Id(membership.getId()).stream()
                .map(UserRole::getRole)
                .anyMatch(r -> COORDINATOR_ROLE.equals(r.getCode()));
        if (!isCoordinator) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The selected user cannot be assigned to this patient.");
        }
        return membership;
    }

    /** Resolve a same-tenant user's display name (best-effort; empty string if the membership is gone). */
    private String nameOf(UUID organizationId, UUID userId) {
        return memberships.findByOrganization_IdAndAppUser_Id(organizationId, userId)
                .map(m -> m.getAppUser().getFullName())
                .orElse("");
    }

    /** Resolve the tenant and confirm the patient is in it (else secure 404). Returns the organization id. */
    private UUID requirePatientInTenant(UUID patientId) {
        UUID organizationId = userContext.requireOrganizationId();
        patients.findByIdAndOrganizationId(patientId, organizationId).orElseThrow(NotFoundException::new);
        return organizationId;
    }
}
