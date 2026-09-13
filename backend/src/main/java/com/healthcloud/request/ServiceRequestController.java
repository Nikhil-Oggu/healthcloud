package com.healthcloud.request;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-request API. Thin controller (§31.5): tenant scoping, authorization, and the create
 * transaction live in {@link ServiceRequestService}. Every route is authenticated and tenant-scoped.
 */
@RestController
@RequestMapping("/api/v1/requests")
public class ServiceRequestController {

    private final ServiceRequestService service;
    private final RequestAssignmentService assignmentService;

    public ServiceRequestController(ServiceRequestService service,
                                    RequestAssignmentService assignmentService) {
        this.service = service;
        this.assignmentService = assignmentService;
    }

    /** All requests in the caller's organization, optionally filtered to one patient. */
    @GetMapping
    public List<ServiceRequestDto> list(@RequestParam(required = false) UUID patientId) {
        return service.list(Optional.ofNullable(patientId));
    }

    /** One request by id, scoped to the caller's organization (404 across tenants). */
    @GetMapping("/{id}")
    public ServiceRequestDto getOne(@PathVariable UUID id) {
        return service.getById(id);
    }

    /** Create a DRAFT request for a patient in the caller's organization. */
    @PostMapping
    public ResponseEntity<ServiceRequestDto> create(@Valid @RequestBody ServiceRequestCreateRequest request) {
        ServiceRequestDto created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/requests/" + created.id())).body(created);
    }

    /** Apply a controlled status transition (§14.6) — backend-validated, optimistic-locked. */
    @PatchMapping("/{id}/status")
    public ServiceRequestDto changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest change) {
        return service.changeStatus(id, change);
    }

    /** The request's status timeline (append-only history). */
    @GetMapping("/{id}/history")
    public List<RequestStatusHistoryDto> history(@PathVariable UUID id) {
        return service.getHistory(id);
    }

    /** Add a comment to a request in the caller's tenant (participant roles only). */
    @PostMapping("/{id}/comments")
    public ResponseEntity<RequestCommentDto> addComment(
            @PathVariable UUID id, @Valid @RequestBody AddCommentRequest request) {
        RequestCommentDto created = service.addComment(id, request);
        return ResponseEntity
                .created(URI.create("/api/v1/requests/" + id + "/comments/" + created.id()))
                .body(created);
    }

    /** The request's comments (oldest first), scoped to the caller's tenant. */
    @GetMapping("/{id}/comments")
    public List<RequestCommentDto> comments(@PathVariable UUID id) {
        return service.getComments(id);
    }

    /** The current active assignment for a request (null if unassigned), scoped to the caller's tenant. */
    @GetMapping("/{id}/assignment")
    public RequestAssignmentDto assignment(@PathVariable UUID id) {
        return assignmentService.getCurrentAssignment(id);
    }

    /** Candidate assignees (same-tenant providers/reviewers) — coordinator/admin only. */
    @GetMapping("/{id}/assignable-users")
    public List<AssignableUserDto> assignableUsers(@PathVariable UUID id) {
        return assignmentService.listAssignableUsers(id);
    }

    /** Assign or reassign a request (coordinator/admin); first assignment advances TRIAGED → ASSIGNED. */
    @PutMapping("/{id}/assignment")
    public RequestAssignmentDto assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return assignmentService.assign(id, request);
    }
}
