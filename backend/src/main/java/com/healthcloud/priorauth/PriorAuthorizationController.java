package com.healthcloud.priorauth;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prior-authorization API (source-of-truth §Phase 6). Thin controller (§31.5): tenant scoping, the
 * object/relationship gate, the one-transaction intake/decision and code/plan validation live in
 * {@link PriorAuthorizationService}. A top-level resource gated by its patient (like claims), so a reviewer gets
 * a cross-patient work queue while a provider sees only assigned patients' authorizations. Every route is
 * authenticated and tenant-scoped (another tenant's authorization is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/prior-authorizations")
public class PriorAuthorizationController {

    private final PriorAuthorizationService service;

    public PriorAuthorizationController(PriorAuthorizationService service) {
        this.service = service;
    }

    /** Prior authorizations in the caller's tenant (header-only), optionally filtered to a patient and/or status. */
    @GetMapping
    public List<PriorAuthorizationSummaryDto> list(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) PriorAuthorizationStatus status) {
        return service.list(Optional.ofNullable(patientId), Optional.ofNullable(status));
    }

    /** One prior authorization by id, scoped to the caller's tenant (404 across tenants / if unreachable). */
    @GetMapping("/{authId}")
    public PriorAuthorizationDto getOne(@PathVariable UUID authId) {
        return service.getById(authId);
    }

    /** Request a prior authorization (REQUESTED) for a patient the caller can reach. */
    @PostMapping
    public ResponseEntity<PriorAuthorizationDto> create(
            @Valid @RequestBody CreatePriorAuthorizationRequest request) {
        PriorAuthorizationDto created = service.request(request);
        return ResponseEntity.created(URI.create("/api/v1/prior-authorizations/" + created.id())).body(created);
    }

    /** Apply a controlled transition (approve/deny/cancel) — backend-validated, optimistic-locked. */
    @PatchMapping("/{authId}/status")
    public PriorAuthorizationDto changeStatus(
            @PathVariable UUID authId, @Valid @RequestBody PriorAuthStatusChangeRequest change) {
        return service.changeStatus(authId, change);
    }

    /** The authorization's status timeline (append-only history). */
    @GetMapping("/{authId}/history")
    public List<PriorAuthStatusHistoryDto> history(@PathVariable UUID authId) {
        return service.getHistory(authId);
    }
}
