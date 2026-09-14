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
 * Provider-patient assignment API, nested under the patient (source-of-truth §14.3). Thin controller
 * (§31.5): tenant scoping, authorization, and the assign/revoke logic live in
 * {@link ProviderPatientAssignmentService}. Every route is authenticated and tenant-scoped (a patient in
 * another tenant is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/provider-assignments")
public class ProviderPatientAssignmentController {

    private final ProviderPatientAssignmentService service;

    public ProviderPatientAssignmentController(ProviderPatientAssignmentService service) {
        this.service = service;
    }

    /** A patient's current (ACTIVE/PENDING) provider assignments. */
    @GetMapping
    public List<ProviderPatientAssignmentDto> list(@PathVariable UUID patientId) {
        return service.listCurrent(patientId);
    }

    /** Assign a same-tenant provider to the patient (coordinator/admin only). */
    @PostMapping
    public ResponseEntity<ProviderPatientAssignmentDto> assign(
            @PathVariable UUID patientId, @Valid @RequestBody AssignProviderRequest request) {
        ProviderPatientAssignmentDto created = service.assign(patientId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/patients/" + patientId + "/provider-assignments/" + created.id()))
                .body(created);
    }

    /** Revoke a current assignment with immediate effect (optimistic-locked). */
    @PostMapping("/{assignmentId}/revoke")
    public ProviderPatientAssignmentDto revoke(
            @PathVariable UUID patientId, @PathVariable UUID assignmentId,
            @Valid @RequestBody RevokeProviderAssignmentRequest request) {
        return service.revoke(patientId, assignmentId, request);
    }
}
