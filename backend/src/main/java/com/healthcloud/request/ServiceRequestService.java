package com.healthcloud.request;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientAccessGuard;
import com.healthcloud.patient.PatientRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-request reads and creation, always scoped to the caller's tenant (org derived from the
 * backend context, never the client). Creation follows the §31.6 one-transaction pattern: the domain
 * row and its initial status-history row are written atomically.
 *
 * <p>Scope note (this slice): create a DRAFT + read. The controlled state-machine transitions live in
 * the next slice.
 */
@Service
@Transactional(readOnly = true)
public class ServiceRequestService {

    /** Roles allowed to create a request (reviewers/auditors cannot). */
    private static final String[] CREATE_ROLES = {"PATIENT", "PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** Roles allowed to comment on a request — the workflow participants (read-only roles cannot). */
    private static final String[] COMMENT_ROLES = {"PATIENT", "PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    private final ServiceRequestRepository requests;
    private final RequestStatusHistoryRepository history;
    private final RequestCommentRepository comments;
    private final PatientRepository patients;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public ServiceRequestService(ServiceRequestRepository requests,
                                 RequestStatusHistoryRepository history,
                                 RequestCommentRepository comments,
                                 PatientRepository patients,
                                 PatientAccessGuard accessGuard,
                                 UserContextAccessor userContext) {
        this.requests = requests;
        this.history = history;
        this.comments = comments;
        this.patients = patients;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /** Create a DRAFT request for a patient in the caller's tenant; records the initial history row. */
    @Transactional
    public ServiceRequestDto create(ServiceRequestCreateRequest request) {
        userContext.requireAnyRole(CREATE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        // The patient must exist in the caller's tenant. If not (incl. another tenant's id) → 404,
        // so we never leak existence and never link across tenants.
        patients.findByIdAndOrganizationId(request.patientId(), organizationId)
                .orElseThrow(NotFoundException::new);

        ServiceRequestPriority priority =
                request.priority() != null ? request.priority() : ServiceRequestPriority.NORMAL;

        ServiceRequest saved = requests.save(new ServiceRequest(
                organizationId,
                request.patientId(),
                request.type(),
                priority,
                request.title(),
                request.description(),
                caller.userId()));

        // §31.6: domain change + status history in one transaction. from=null marks creation.
        history.save(new RequestStatusHistory(
                organizationId,
                saved.getId(),
                null,
                ServiceRequestStatus.DRAFT,
                caller.userId(),
                "Request created",
                CorrelationId.current()));

        return ServiceRequestDto.from(saved);
    }

    /** One request in the caller's tenant, gated by its patient (§21 layer 6), or a secure 404. */
    public ServiceRequestDto getById(UUID id) {
        return ServiceRequestDto.from(requireAccessibleRequest(id));
    }

    /**
     * Requests in the caller's tenant, optionally filtered to one patient. A request is gated by its patient
     * (§21 layer 6), so a provider sees only requests for patients they are actively assigned to:
     * <ul>
     *   <li>filtered to a patient the caller cannot reach → secure 404 (consistent with {@code GET /patients/{id}});
     *   <li>unfiltered, a provider-gated caller sees only their active patients' requests; broad roles see all.
     * </ul>
     */
    public List<ServiceRequestDto> list(Optional<UUID> patientId) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        if (patientId.isPresent()) {
            // Reuse the patient gate: an inaccessible patient (another tenant, or unassigned provider) → 404.
            accessGuard.requireAccessibleInTenant(patientId.get());
            return requests
                    .findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(organizationId, patientId.get())
                    .stream().map(ServiceRequestDto::from).toList();
        }

        List<ServiceRequest> found;
        if (accessGuard.isProviderGated(caller)) {
            Set<UUID> visible = accessGuard.activePatientIdsFor(organizationId, caller.userId());
            found = visible.isEmpty()
                    ? List.of()
                    : requests.findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc(organizationId, visible);
        } else {
            found = requests.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
        }
        return found.stream().map(ServiceRequestDto::from).toList();
    }

    /**
     * Load a request in the caller's tenant and confirm the caller may reach its patient (§21 layer 6),
     * else a secure 404. The single choke point for every request read/write that names one request.
     */
    private ServiceRequest requireAccessibleRequest(UUID id) {
        UUID organizationId = userContext.requireOrganizationId();
        ServiceRequest request = requests.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(request.getPatientId());
        return request;
    }

    /**
     * Apply a controlled state transition (§14.6). In one transaction: validate the move is legal and
     * the caller is allowed, enforce optimistic locking, update the status, and append a history row.
     * Order of checks: exists → legal move → role → reason → version.
     */
    @Transactional
    public ServiceRequestDto changeStatus(UUID id, StatusChangeRequest change) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        // Tenant + object/relationship gate: an unassigned provider gets a secure 404, so the request's
        // state is never revealed before the transition checks run.
        ServiceRequest request = requireAccessibleRequest(id);

        ServiceRequestStatus from = request.getStatus();
        ServiceRequestStatus to = change.targetStatus();

        // ASSIGNED is reached only by assigning a responsible user (PUT .../assignment), which records the
        // assignee and advances the status atomically — never by a bare status change (would leave no assignee).
        if (to == ServiceRequestStatus.ASSIGNED) {
            throw new InvalidStateTransitionException("Assign a user to move a request to ASSIGNED.");
        }
        // 1. Is this a legal move at all? (e.g. DRAFT→APPROVED, or leaving a terminal state → 409)
        if (!RequestTransitions.isAllowed(from, to)) {
            throw new InvalidStateTransitionException(
                    "Cannot change status from " + from + " to " + to + ".");
        }
        // 2. May this caller perform it? (§12.1 function permission)
        if (!RequestTransitions.isRoleAllowed(from, to, caller.roles())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
        }
        // 3. Reason required for some transitions (cancel/reject).
        if (RequestTransitions.reasonRequired(to) && (change.reason() == null || change.reason().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A reason is required to " + to + " this request.");
        }
        // 4. Optimistic locking: reject a stale caller (someone else moved the request first).
        if (request.getVersion() != change.expectedVersion()) {
            throw new ConflictException("This request was modified by someone else; reload and try again.");
        }

        request.setStatus(to);
        ServiceRequest saved = requests.saveAndFlush(request); // bump @Version; response carries the new one

        // §31.6: the history row is written in the same transaction as the status change.
        history.save(new RequestStatusHistory(
                organizationId, request.getId(), from, to, caller.userId(),
                change.reason(), CorrelationId.current()));

        return ServiceRequestDto.from(saved);
    }

    /** The request's status timeline (append-only history), tenant + relationship gated (secure 404). */
    public List<RequestStatusHistoryDto> getHistory(UUID id) {
        UUID organizationId = userContext.requireOrganizationId();
        requireAccessibleRequest(id); // 404 if not in tenant or the caller can't reach its patient
        return history.findByOrganizationIdAndServiceRequestIdOrderByCreatedAtAsc(organizationId, id).stream()
                .map(RequestStatusHistoryDto::from)
                .toList();
    }

    /**
     * Add a comment to a request in the caller's tenant. Participant roles only (read-only roles → 403);
     * the request must be in the caller's tenant (else secure 404). Org + author are stamped from context.
     */
    @Transactional
    public RequestCommentDto addComment(UUID requestId, AddCommentRequest request) {
        userContext.requireAnyRole(COMMENT_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        // Tenant + object/relationship gate: another tenant's id, or a request whose patient the caller
        // cannot reach (e.g. an unassigned provider), is a secure 404 (no existence leak).
        requireAccessibleRequest(requestId);

        RequestComment saved = comments.save(
                new RequestComment(organizationId, requestId, caller.userId(), request.body()));
        return RequestCommentDto.from(saved);
    }

    /** The request's comments (oldest first), tenant + relationship gated (secure 404). */
    public List<RequestCommentDto> getComments(UUID requestId) {
        UUID organizationId = userContext.requireOrganizationId();
        requireAccessibleRequest(requestId); // 404 if not in tenant or the caller can't reach its patient
        return comments.findByOrganizationIdAndServiceRequestIdOrderByCreatedAtAsc(organizationId, requestId)
                .stream()
                .map(RequestCommentDto::from)
                .toList();
    }
}
