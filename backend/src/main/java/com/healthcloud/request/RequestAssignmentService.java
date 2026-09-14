package com.healthcloud.request;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.identity.MembershipStatus;
import com.healthcloud.identity.OrganizationMembership;
import com.healthcloud.identity.OrganizationMembershipRepository;
import com.healthcloud.identity.UserRole;
import com.healthcloud.identity.UserRoleRepository;
import com.healthcloud.patient.PatientAccessGuard;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigning a service request to a responsible user (source-of-truth §14.5). The assigner is a
 * coordinator/admin; the assignee is a same-tenant provider or claims reviewer. Assignment is the sole
 * path to the {@code ASSIGNED} status: assigning a TRIAGED request advances it to ASSIGNED and appends a
 * status-history row in one transaction (§31.6); reassigning an ASSIGNED request just swaps the assignee
 * (superseding the prior row). Everything is derived from the backend context — never from the client.
 */
@Service
@Transactional(readOnly = true)
public class RequestAssignmentService {

    /** Roles allowed to assign (§9.3 coordinator, §9.5 admin). */
    private static final String[] ASSIGN_ROLES = {"CARE_COORDINATOR", "ORG_ADMIN"};

    /** Roles a request may be assigned to — the coordinator journey's "provider/reviewer". */
    private static final Set<String> ASSIGNABLE_ROLES = Set.of("PROVIDER", "CLAIMS_REVIEWER");

    private final ServiceRequestRepository requests;
    private final RequestAssignmentRepository assignments;
    private final RequestStatusHistoryRepository history;
    private final OrganizationMembershipRepository memberships;
    private final UserRoleRepository userRoles;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public RequestAssignmentService(ServiceRequestRepository requests,
                                    RequestAssignmentRepository assignments,
                                    RequestStatusHistoryRepository history,
                                    OrganizationMembershipRepository memberships,
                                    UserRoleRepository userRoles,
                                    PatientAccessGuard accessGuard,
                                    UserContextAccessor userContext) {
        this.requests = requests;
        this.assignments = assignments;
        this.history = history;
        this.memberships = memberships;
        this.userRoles = userRoles;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * The current active assignment for a request in the caller's tenant, or {@code null} if unassigned.
     * Tenant + object/relationship gated by the request's patient (§21 layer 6): an unassigned provider
     * gets a secure 404, so this read cannot be used to learn about a request they cannot otherwise reach.
     */
    public RequestAssignmentDto getCurrentAssignment(UUID requestId) {
        UUID organizationId = userContext.requireOrganizationId();
        ServiceRequest request = requests.findByIdAndOrganizationId(requestId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(request.getPatientId());
        return assignments
                .findByOrganizationIdAndServiceRequestIdAndStatus(
                        organizationId, requestId, RequestAssignmentStatus.ACTIVE)
                .map(a -> RequestAssignmentDto.from(a, nameOf(organizationId, a.getAssigneeUserId())))
                .orElse(null);
    }

    /** Same-tenant providers/reviewers who can be assigned this request (minimum-necessary fields). */
    public List<AssignableUserDto> listAssignableUsers(UUID requestId) {
        userContext.requireAnyRole(ASSIGN_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        requests.findByIdAndOrganizationId(requestId, organizationId).orElseThrow(NotFoundException::new);

        return memberships.findByOrganization_Id(organizationId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .flatMap(m -> assignableRole(m).stream()
                        .map(role -> new AssignableUserDto(
                                m.getAppUser().getId(), m.getAppUser().getFullName(), role)))
                .sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()))
                .toList();
    }

    /**
     * Assign (or reassign) a request to a same-tenant provider/reviewer. Order of checks:
     * exists → assignable status → optimistic version → eligible assignee. Then, in one transaction,
     * supersede any current assignment, insert the new ACTIVE one, and (from TRIAGED) advance to ASSIGNED.
     */
    @Transactional
    public RequestAssignmentDto assign(UUID requestId, AssignRequest request) {
        userContext.requireAnyRole(ASSIGN_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        ServiceRequest sr = requests.findByIdAndOrganizationId(requestId, organizationId)
                .orElseThrow(NotFoundException::new);

        // A request is assignable only once triaged: TRIAGED (first assignment → ASSIGNED) or ASSIGNED (reassign).
        ServiceRequestStatus from = sr.getStatus();
        boolean advancesToAssigned = from == ServiceRequestStatus.TRIAGED;
        if (from != ServiceRequestStatus.TRIAGED && from != ServiceRequestStatus.ASSIGNED) {
            throw new InvalidStateTransitionException(
                    "A request can only be assigned when it is TRIAGED or ASSIGNED (current: " + from + ").");
        }

        // Optimistic locking: the caller must be acting on the version they last saw.
        if (sr.getVersion() != request.expectedVersion()) {
            throw new ConflictException("This request was modified by someone else; reload and try again.");
        }

        // The assignee must be a same-tenant provider or claims reviewer (else 400 — no existence leak of users).
        OrganizationMembership assigneeMembership =
                memberships.findByOrganization_IdAndAppUser_Id(organizationId, request.assigneeUserId())
                        .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                        .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                                "The selected user cannot be assigned to this request."));
        String assigneeRole = assignableRole(assigneeMembership)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "The selected user cannot be assigned to this request."));

        // Supersede the current active assignment first (flush before inserting, to honor the unique-active index).
        assignments.findByOrganizationIdAndServiceRequestIdAndStatus(
                        organizationId, requestId, RequestAssignmentStatus.ACTIVE)
                .ifPresent(current -> {
                    current.supersede();
                    assignments.saveAndFlush(current);
                });

        RequestAssignment saved = assignments.save(new RequestAssignment(
                organizationId, requestId, request.assigneeUserId(), caller.userId(), assigneeRole));

        // First assignment advances TRIAGED → ASSIGNED and records it on the status timeline (§31.6).
        if (advancesToAssigned) {
            sr.setStatus(ServiceRequestStatus.ASSIGNED);
            requests.saveAndFlush(sr); // bump @Version
            history.save(new RequestStatusHistory(
                    organizationId, requestId, from, ServiceRequestStatus.ASSIGNED, caller.userId(),
                    "Assigned to " + assigneeMembership.getAppUser().getFullName(), CorrelationId.current()));
        }

        return RequestAssignmentDto.from(saved, assigneeMembership.getAppUser().getFullName());
    }

    /** The assignable role a membership holds (PROVIDER/CLAIMS_REVIEWER), if any. */
    private Optional<String> assignableRole(OrganizationMembership membership) {
        return userRoles.findByMembership_Id(membership.getId()).stream()
                .map(UserRole::getRole)
                .map(r -> r.getCode())
                .filter(ASSIGNABLE_ROLES::contains)
                .findFirst();
    }

    /** Resolve a same-tenant user's display name (best-effort; empty string if the membership is gone). */
    private String nameOf(UUID organizationId, UUID userId) {
        return memberships.findByOrganization_IdAndAppUser_Id(organizationId, userId)
                .map(m -> m.getAppUser().getFullName())
                .orElse("");
    }
}
