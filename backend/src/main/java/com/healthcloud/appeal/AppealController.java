package com.healthcloud.appeal;

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
 * Appeal API (source-of-truth §Phase 6). Thin controller (§31.5): tenant scoping, the object/relationship gate
 * (via the appeal's/claim's patient), the one-transaction intake/decision and the appealable-state/open-appeal
 * validation live in {@link AppealService}. A top-level resource gated by its patient (like claims), so a reviewer
 * gets a cross-patient work queue while a provider sees only assigned patients' appeals. Every route is
 * authenticated and tenant-scoped (another tenant's appeal is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/appeals")
public class AppealController {

    private final AppealService service;

    public AppealController(AppealService service) {
        this.service = service;
    }

    /** Appeals in the caller's tenant (header-only), optionally filtered to a claim and/or status. */
    @GetMapping
    public List<AppealSummaryDto> list(
            @RequestParam(required = false) UUID claimId,
            @RequestParam(required = false) AppealStatus status) {
        return service.list(Optional.ofNullable(claimId), Optional.ofNullable(status));
    }

    /** One appeal by id, scoped to the caller's tenant (404 across tenants / if unreachable). */
    @GetMapping("/{appealId}")
    public AppealDto getOne(@PathVariable UUID appealId) {
        return service.getById(appealId);
    }

    /** Submit an appeal (SUBMITTED) against a claim the caller can reach. */
    @PostMapping
    public ResponseEntity<AppealDto> create(@Valid @RequestBody CreateAppealRequest request) {
        AppealDto created = service.submit(request);
        return ResponseEntity.created(URI.create("/api/v1/appeals/" + created.id())).body(created);
    }

    /** Apply a controlled transition (uphold/overturn/withdraw) — backend-validated, optimistic-locked. */
    @PatchMapping("/{appealId}/status")
    public AppealDto changeStatus(
            @PathVariable UUID appealId, @Valid @RequestBody AppealStatusChangeRequest change) {
        return service.changeStatus(appealId, change);
    }

    /** The appeal's status timeline (append-only history). */
    @GetMapping("/{appealId}/history")
    public List<AppealStatusHistoryDto> history(@PathVariable UUID appealId) {
        return service.getHistory(appealId);
    }
}
