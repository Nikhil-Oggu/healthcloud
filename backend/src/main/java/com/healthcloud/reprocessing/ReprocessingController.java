package com.healthcloud.reprocessing;

import com.healthcloud.common.PageRequests;
import com.healthcloud.common.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reprocessing-batch API (source-of-truth §Phase 6). Thin controller (§31.5): the role gate, plan validation,
 * claim selection, the per-claim re-adjudication and the batch/item recording all live in
 * {@link ReprocessingService}. A batch is a reviewer/admin job over a coverage plan; every route is authenticated
 * and tenant-scoped (another tenant's batch is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/reprocessing-batches")
public class ReprocessingController {

    /** Fields a caller may sort the reprocessing queue by (allowlisted — an unknown field is a clean 400). */
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "batchNumber", "status");

    /** Default ordering when the caller supplies no {@code sort}: newest first (the queue's prior behavior). */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ReprocessingService service;

    public ReprocessingController(ReprocessingService service) {
        this.service = service;
    }

    /**
     * A page of batches in the caller's tenant (header-only), optionally filtered to a status (§Phase 9). Paging/
     * sorting come from {@code page}/{@code size}/{@code sort}; an unknown sort field is a 400. Returns a
     * {@link PageResponse}.
     */
    @GetMapping
    public PageResponse<ReprocessingBatchSummaryDto> list(
            @RequestParam(required = false) ReprocessingBatchStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.toPageable(page, size, sort, SORTABLE_FIELDS, DEFAULT_SORT);
        return service.list(Optional.ofNullable(status), pageable);
    }

    /** One batch by id (header + per-claim items), scoped to the caller's tenant (404 across tenants). */
    @GetMapping("/{batchId}")
    public ReprocessingBatchDto getOne(@PathVariable UUID batchId) {
        return service.getById(batchId);
    }

    /** Create and run a reprocessing batch for a coverage plan (CLAIMS_REVIEWER/ORG_ADMIN); runs synchronously. */
    @PostMapping
    public ResponseEntity<ReprocessingBatchDto> create(
            @Valid @RequestBody CreateReprocessingBatchRequest request) {
        ReprocessingBatchDto created = service.createAndRun(request);
        return ResponseEntity.created(URI.create("/api/v1/reprocessing-batches/" + created.id())).body(created);
    }
}
