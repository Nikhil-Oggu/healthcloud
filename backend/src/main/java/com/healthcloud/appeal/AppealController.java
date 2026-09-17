package com.healthcloud.appeal;

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
 * Appeal API (source-of-truth §Phase 6). Thin controller (§31.5): tenant scoping, the object/relationship gate
 * (via the appeal's/claim's patient), the one-transaction intake/decision and the appealable-state/open-appeal
 * validation live in {@link AppealService}. A top-level resource gated by its patient (like claims), so a reviewer
 * gets a cross-patient work queue while a provider sees only assigned patients' appeals. Every route is
 * authenticated and tenant-scoped (another tenant's appeal is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/appeals")
public class AppealController {

    /** Fields a caller may sort the appeal queue by (allowlisted — an unknown field is a clean 400). */
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "appealNumber", "status");

    /** Default ordering when the caller supplies no {@code sort}: newest first (the queue's prior behavior). */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final AppealService service;

    public AppealController(AppealService service) {
        this.service = service;
    }

    /**
     * A page of appeals in the caller's tenant (header-only), optionally filtered to a claim and/or status
     * (§Phase 9). Paging/sorting come from {@code page}/{@code size}/{@code sort}; an out-of-range {@code size} is
     * clamped and an unknown sort field is a 400. Returns a {@link PageResponse}.
     */
    @GetMapping
    public PageResponse<AppealSummaryDto> list(
            @RequestParam(required = false) UUID claimId,
            @RequestParam(required = false) AppealStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.toPageable(page, size, sort, SORTABLE_FIELDS, DEFAULT_SORT);
        return service.list(
                Optional.ofNullable(claimId), Optional.ofNullable(status), Optional.ofNullable(q), pageable);
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
