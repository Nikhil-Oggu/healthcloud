package com.healthcloud.claim;

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
 * Claim API. Thin controller (§31.5): tenant scoping, the object/relationship gate, the create transaction and
 * procedure-code validation live in {@link ClaimService}. A top-level resource gated by its patient (like
 * service requests), so a reviewer gets a cross-patient work queue while a provider sees only assigned
 * patients' claims. Every route is authenticated and tenant-scoped (another tenant's claim is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    /** Fields a caller may sort the claims queue by (allowlisted — an unknown field is a clean 400). */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("createdAt", "serviceDate", "totalChargeAmount", "status", "claimNumber");

    /** Default ordering when the caller supplies no {@code sort}: newest first (the queue's prior behavior). */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    /**
     * A page of claims in the caller's tenant (header-only), optionally filtered to one patient, a status, and/or a
     * free-text search term {@code q} (§Phase 9). {@code q} is a case-insensitive "contains" match on the claim
     * number (a PHI-free identifier). Paging/sorting come from {@code page}/{@code size}/{@code sort} (e.g.
     * {@code sort=serviceDate,desc}); an out-of-range {@code size} is clamped and an unknown sort field is a 400.
     * Returns a {@link PageResponse}.
     */
    @GetMapping
    public PageResponse<ClaimSummaryDto> list(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.toPageable(page, size, sort, SORTABLE_FIELDS, DEFAULT_SORT);
        return service.list(
                Optional.ofNullable(patientId), Optional.ofNullable(status), Optional.ofNullable(q), pageable);
    }

    /** One claim (header + lines) by id, scoped to the caller's tenant (404 across tenants / if unreachable). */
    @GetMapping("/{claimId}")
    public ClaimDto getOne(@PathVariable UUID claimId) {
        return service.getById(claimId);
    }

    /** Create a DRAFT claim (header + lines) for a patient the caller can reach. */
    @PostMapping
    public ResponseEntity<ClaimDto> create(@Valid @RequestBody CreateClaimRequest request) {
        ClaimDto created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/claims/" + created.id())).body(created);
    }

    /** Apply a controlled status transition (submit/accept/reject/cancel) — backend-validated, optimistic-locked. */
    @PatchMapping("/{claimId}/status")
    public ClaimDto changeStatus(
            @PathVariable UUID claimId, @Valid @RequestBody ClaimStatusChangeRequest change) {
        return service.changeStatus(claimId, change);
    }

    /** The claim's status timeline (append-only history). */
    @GetMapping("/{claimId}/history")
    public List<ClaimStatusHistoryDto> history(@PathVariable UUID claimId) {
        return service.getHistory(claimId);
    }
}
