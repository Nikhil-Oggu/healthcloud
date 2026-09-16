package com.healthcloud.claimreview;

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
 * Manual-review API (source-of-truth §Phase 6). Thin controller (§31.5): tenant scoping, the object/relationship
 * gate (via the review's/claim's patient), the one-transaction intake/resolution and the one-open-review-per-claim
 * validation live in {@link ClaimReviewService}. A top-level resource gated by its patient (like appeals), so a
 * reviewer gets a cross-patient work queue while a provider sees only assigned patients' reviews. Every route is
 * authenticated and tenant-scoped (another tenant's review is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/claim-reviews")
public class ClaimReviewController {

    private final ClaimReviewService service;

    public ClaimReviewController(ClaimReviewService service) {
        this.service = service;
    }

    /** Reviews in the caller's tenant (header-only), optionally filtered to a claim and/or status. */
    @GetMapping
    public List<ClaimReviewSummaryDto> list(
            @RequestParam(required = false) UUID claimId,
            @RequestParam(required = false) ClaimReviewStatus status) {
        return service.list(Optional.ofNullable(claimId), Optional.ofNullable(status));
    }

    /** One review by id, scoped to the caller's tenant (404 across tenants / if unreachable). */
    @GetMapping("/{reviewId}")
    public ClaimReviewDto getOne(@PathVariable UUID reviewId) {
        return service.getById(reviewId);
    }

    /** Open a manual review (OPEN) on a claim the caller can reach. */
    @PostMapping
    public ResponseEntity<ClaimReviewDto> create(@Valid @RequestBody CreateClaimReviewRequest request) {
        ClaimReviewDto created = service.open(request);
        return ResponseEntity.created(URI.create("/api/v1/claim-reviews/" + created.id())).body(created);
    }

    /** Apply a controlled transition (resolve/cancel) — backend-validated, optimistic-locked. */
    @PatchMapping("/{reviewId}/status")
    public ClaimReviewDto changeStatus(
            @PathVariable UUID reviewId, @Valid @RequestBody ClaimReviewStatusChangeRequest change) {
        return service.changeStatus(reviewId, change);
    }

    /** The review's status timeline (append-only history). */
    @GetMapping("/{reviewId}/history")
    public List<ClaimReviewStatusHistoryDto> history(@PathVariable UUID reviewId) {
        return service.getHistory(reviewId);
    }
}
