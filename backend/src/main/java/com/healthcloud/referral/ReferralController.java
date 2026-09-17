package com.healthcloud.referral;

import com.healthcloud.common.PageRequests;
import com.healthcloud.common.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** Fields a caller may sort the referral queue by (allowlisted — an unknown field is a clean 400). */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("createdAt", "referralNumber", "specialty", "reasonCode", "status");

    /** Default ordering when the caller supplies no {@code sort}: newest first (the queue's prior behavior). */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ReferralService service;

    public ReferralController(ReferralService service) {
        this.service = service;
    }

    /**
     * A page of referrals in the caller's tenant (header-only), optionally filtered to a patient and/or status
     * (§Phase 9). Paging/sorting come from {@code page}/{@code size}/{@code sort}; an out-of-range {@code size} is
     * clamped and an unknown sort field is a 400. Returns a {@link PageResponse}.
     */
    @GetMapping
    public PageResponse<ReferralSummaryDto> list(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) ReferralStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.toPageable(page, size, sort, SORTABLE_FIELDS, DEFAULT_SORT);
        return service.list(
                Optional.ofNullable(patientId), Optional.ofNullable(status), Optional.ofNullable(q), pageable);
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
