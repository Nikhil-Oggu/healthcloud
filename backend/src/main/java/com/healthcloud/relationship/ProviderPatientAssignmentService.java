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
import com.healthcloud.patient.PatientRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages provider-patient care relationships (source-of-truth §14.3). A coordinator/admin assigns a
 * same-tenant PROVIDER to a patient; the relationship is effective-dated and auditable (assign/revoke are
 * recorded, never deleted). At most one CURRENT (ACTIVE/PENDING) assignment exists per (patient, provider).
 *
 * <p>Scope note (this slice): this only RECORDS relationships. The object/relationship access gate that
 * consumes them — "a provider may read only assigned patients" (§12.1, §21 layer 6) — and its wiring into
 * PROVIDER/CARE_TEAM consent scope arrive in the next slice. {@code care_coordinator_assignment} is separate.
 */
@Service
@Transactional(readOnly = true)
public class ProviderPatientAssignmentService {

    /** Roles allowed to manage assignments (§9 coordinator/admin). */
    private static final String[] ASSIGN_ROLES = {"CARE_COORDINATOR", "ORG_ADMIN"};

    /** The role a provider-patient assignee must hold. */
    private static final String PROVIDER_ROLE = "PROVIDER";

    /** The "current" statuses — in force or scheduled to be (not terminal history). */
    private static final List<ProviderPatientAssignmentStatus> CURRENT =
            List.of(ProviderPatientAssignmentStatus.ACTIVE, ProviderPatientAssignmentStatus.PENDING);

    private final ProviderPatientAssignmentRepository assignments;
    private final PatientRepository patients;
    private final OrganizationMembershipRepository memberships;
    private final UserRoleRepository userRoles;
    private final UserContextAccessor userContext;

    public ProviderPatientAssignmentService(ProviderPatientAssignmentRepository assignments,
                                            PatientRepository patients,
                                            OrganizationMembershipRepository memberships,
                                            UserRoleRepository userRoles,
                                            UserContextAccessor userContext) {
        this.assignments = assignments;
        this.patients = patients;
        this.memberships = memberships;
        this.userRoles = userRoles;
        this.userContext = userContext;
    }

    /** A patient's current (ACTIVE/PENDING) provider assignments, oldest first. */
    public List<ProviderPatientAssignmentDto> listCurrent(UUID patientId) {
        UUID organizationId = requirePatientInTenant(patientId);
        return assignments
                .findByOrganizationIdAndPatientIdAndStatusInOrderByAssignedAtAsc(organizationId, patientId, CURRENT)
                .stream()
                .map(a -> ProviderPatientAssignmentDto.from(a, nameOf(organizationId, a.getProviderUserId())))
                .toList();
    }

    /**
     * Assign a same-tenant provider to a patient. Requires a coordinator/admin role; the patient must be in
     * the caller's tenant (else 404); the target must be a same-tenant PROVIDER (else 400 — no existence
     * leak); a duplicate current assignment for the pair is a 409.
     */
    @Transactional
    public ProviderPatientAssignmentDto assign(UUID patientId, AssignProviderRequest request) {
        userContext.requireAnyRole(ASSIGN_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = requirePatientInTenant(patientId);

        OrganizationMembership provider = requireSameTenantProvider(organizationId, request.providerUserId());

        LocalDate today = LocalDate.now();
        LocalDate effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : today;
        LocalDate effectiveTo = request.effectiveTo();
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The assignment end date cannot be before its start date.");
        }
        ProviderPatientAssignmentStatus status = effectiveFrom.isAfter(today)
                ? ProviderPatientAssignmentStatus.PENDING
                : ProviderPatientAssignmentStatus.ACTIVE;

        boolean alreadyAssigned = assignments
                .findByOrganizationIdAndPatientIdAndStatusInOrderByAssignedAtAsc(organizationId, patientId, CURRENT)
                .stream()
                .anyMatch(a -> a.getProviderUserId().equals(request.providerUserId()));
        if (alreadyAssigned) {
            throw new ConflictException("That provider is already assigned to this patient.");
        }

        ProviderPatientAssignment saved = assignments.save(new ProviderPatientAssignment(
                organizationId, patientId, request.providerUserId(), caller.userId(),
                status, effectiveFrom, effectiveTo));
        return ProviderPatientAssignmentDto.from(saved, provider.getAppUser().getFullName());
    }

    /**
     * Revoke a current assignment with immediate effect. Requires a coordinator/admin role; the assignment
     * must belong to this patient in the caller's tenant (else 404), be current (else invalid transition),
     * and the caller's {@code expectedVersion} must match (else 409).
     */
    @Transactional
    public ProviderPatientAssignmentDto revoke(UUID patientId, UUID assignmentId,
                                               RevokeProviderAssignmentRequest request) {
        userContext.requireAnyRole(ASSIGN_ROLES);
        UUID organizationId = requirePatientInTenant(patientId);

        ProviderPatientAssignment assignment = assignments.findByIdAndOrganizationId(assignmentId, organizationId)
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
        return ProviderPatientAssignmentDto.from(
                assignments.saveAndFlush(assignment), nameOf(organizationId, assignment.getProviderUserId()));
    }

    /** The target must be an active same-tenant PROVIDER, else 400 (no existence leak of users). */
    private OrganizationMembership requireSameTenantProvider(UUID organizationId, UUID providerUserId) {
        OrganizationMembership membership = memberships
                .findByOrganization_IdAndAppUser_Id(organizationId, providerUserId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "The selected user cannot be assigned to this patient."));
        boolean isProvider = userRoles.findByMembership_Id(membership.getId()).stream()
                .map(UserRole::getRole)
                .anyMatch(r -> PROVIDER_ROLE.equals(r.getCode()));
        if (!isProvider) {
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
