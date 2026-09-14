package com.healthcloud.relationship;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Care-coordinator assignment API, nested under the patient (source-of-truth §14.3). Thin controller
 * (§31.5): tenant scoping, authorization, and the assign/revoke logic live in
 * {@link CareCoordinatorAssignmentService}. Every route is authenticated and tenant-scoped (a patient in
 * another tenant is a secure 404), and reads pass the object/relationship gate.
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/coordinator-assignments")
public class CareCoordinatorAssignmentController {

    private final CareCoordinatorAssignmentService service;

    public CareCoordinatorAssignmentController(CareCoordinatorAssignmentService service) {
        this.service = service;
    }

    /** A patient's current (ACTIVE/PENDING) coordinator assignments. */
    @GetMapping
    public List<CareCoordinatorAssignmentDto> list(@PathVariable UUID patientId) {
        return service.listCurrent(patientId);
    }

    /** Same-tenant coordinators who can be newly assigned to the patient (coordinator/admin only). */
    @GetMapping("/candidates")
    public List<AssignmentCandidateDto> candidates(@PathVariable UUID patientId) {
        return service.listCandidates(patientId);
    }

    /** Assign a same-tenant coordinator to the patient (coordinator/admin only). */
    @PostMapping
    public ResponseEntity<CareCoordinatorAssignmentDto> assign(
            @PathVariable UUID patientId, @Valid @RequestBody AssignCoordinatorRequest request) {
        CareCoordinatorAssignmentDto created = service.assign(patientId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/patients/" + patientId + "/coordinator-assignments/" + created.id()))
                .body(created);
    }

    /** Revoke a current assignment with immediate effect (optimistic-locked). */
    @PostMapping("/{assignmentId}/revoke")
    public CareCoordinatorAssignmentDto revoke(
            @PathVariable UUID patientId, @PathVariable UUID assignmentId,
            @Valid @RequestBody RevokeCoordinatorAssignmentRequest request) {
        return service.revoke(patientId, assignmentId, request);
    }
}
