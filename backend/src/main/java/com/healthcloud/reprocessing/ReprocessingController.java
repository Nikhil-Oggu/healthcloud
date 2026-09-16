package com.healthcloud.reprocessing;

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
 * The reprocessing-batch API (source-of-truth §Phase 6). Thin controller (§31.5): the role gate, plan validation,
 * claim selection, the per-claim re-adjudication and the batch/item recording all live in
 * {@link ReprocessingService}. A batch is a reviewer/admin job over a coverage plan; every route is authenticated
 * and tenant-scoped (another tenant's batch is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/reprocessing-batches")
public class ReprocessingController {

    private final ReprocessingService service;

    public ReprocessingController(ReprocessingService service) {
        this.service = service;
    }

    /** Batches in the caller's tenant (header-only), newest first — the work queue. */
    @GetMapping
    public List<ReprocessingBatchSummaryDto> list() {
        return service.list();
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
