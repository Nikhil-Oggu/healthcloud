package com.healthcloud.request;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientRepository;
import java.util.List;
import java.util.Optional;
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
    private final UserContextAccessor userContext;

    public ServiceRequestService(ServiceRequestRepository requests,
                                 RequestStatusHistoryRepository history,
                                 RequestCommentRepository comments,
                                 PatientRepository patients,
                                 UserContextAccessor userContext) {
        this.requests = requests;
        this.history = history;
        this.comments = comments;
        this.patients = patients;
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

    /** One request in the caller's tenant, or 404 (also for another tenant's id). */
    public ServiceRequestDto getById(UUID id) {
        UUID organizationId = userContext.requireOrganizationId();
        return requests.findByIdAndOrganizationId(id, organizationId)
                .map(ServiceRequestDto::from)
                .orElseThrow(NotFoundException::new);
    }

    /** Requests in the caller's tenant, optionally filtered to one patient. */
    public List<ServiceRequestDto> list(Optional<UUID> patientId) {
        UUID organizationId = userContext.requireOrganizationId();
        List<ServiceRequest> found = patientId
                .map(pid -> requests.findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(organizationId, pid))
                .orElseGet(() -> requests.findByOrganizationIdOrderByCreatedAtDesc(organizationId));
        return found.stream().map(ServiceRequestDto::from).toList();
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

        ServiceRequest request = requests.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(NotFoundException::new);

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

    /** The request's status timeline (append-only history), scoped to the caller's tenant. */
    public List<RequestStatusHistoryDto> getHistory(UUID id) {
        UUID organizationId = userContext.requireOrganizationId();
        // 404 (not empty list) if the request isn't in the caller's tenant — don't leak existence.
        requests.findByIdAndOrganizationId(id, organizationId).orElseThrow(NotFoundException::new);
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

        // The request must exist in the caller's tenant; another tenant's id → 404 (no existence leak).
        requests.findByIdAndOrganizationId(requestId, organizationId).orElseThrow(NotFoundException::new);

        RequestComment saved = comments.save(
                new RequestComment(organizationId, requestId, caller.userId(), request.body()));
        return RequestCommentDto.from(saved);
    }

    /** The request's comments (oldest first), scoped to the caller's tenant. */
    public List<RequestCommentDto> getComments(UUID requestId) {
        UUID organizationId = userContext.requireOrganizationId();
        // 404 (not empty list) if the request isn't in the caller's tenant — don't leak existence.
        requests.findByIdAndOrganizationId(requestId, organizationId).orElseThrow(NotFoundException::new);
        return comments.findByOrganizationIdAndServiceRequestIdOrderByCreatedAtAsc(organizationId, requestId)
                .stream()
                .map(RequestCommentDto::from)
                .toList();
    }
}
