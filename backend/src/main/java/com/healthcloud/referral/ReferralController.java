package com.healthcloud.referral;

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
 * Referral API (source-of-truth §Phase 6). Thin controller (§31.5): tenant scoping, the object/relationship
 * gate, the one-transaction intake/decision and code validation live in {@link ReferralService}. A top-level
 * resource gated by its patient (like claims/prior authorizations), so a coordinator gets a cross-patient work
 * queue while a provider sees only assigned patients' referrals. Every route is authenticated and tenant-scoped
 * (another tenant's referral is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/referrals")
public class ReferralController {

    private final ReferralService service;

    public ReferralController(ReferralService service) {
        this.service = service;
    }

    /** Referrals in the caller's tenant (header-only), optionally filtered to a patient and/or status. */
    @GetMapping
    public List<ReferralSummaryDto> list(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) ReferralStatus status) {
        return service.list(Optional.ofNullable(patientId), Optional.ofNullable(status));
    }

    /** One referral by id, scoped to the caller's tenant (404 across tenants / if unreachable). */
    @GetMapping("/{referralId}")
    public ReferralDto getOne(@PathVariable UUID referralId) {
        return service.getById(referralId);
    }

    /** Request a referral (REQUESTED) for a patient the caller can reach. */
    @PostMapping
    public ResponseEntity<ReferralDto> create(@Valid @RequestBody CreateReferralRequest request) {
        ReferralDto created = service.request(request);
        return ResponseEntity.created(URI.create("/api/v1/referrals/" + created.id())).body(created);
    }

    /** Apply a controlled transition (approve/deny/cancel) — backend-validated, optimistic-locked. */
    @PatchMapping("/{referralId}/status")
    public ReferralDto changeStatus(
            @PathVariable UUID referralId, @Valid @RequestBody ReferralStatusChangeRequest change) {
        return service.changeStatus(referralId, change);
    }

    /** The referral's status timeline (append-only history). */
    @GetMapping("/{referralId}/history")
    public List<ReferralStatusHistoryDto> history(@PathVariable UUID referralId) {
        return service.getHistory(referralId);
    }
}
