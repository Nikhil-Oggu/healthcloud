package com.healthcloud.reprocessing;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Header-only view of a reprocessing batch for list reads (the work queue) — no items, a single query. */
public record ReprocessingBatchSummaryDto(
        UUID id,
        String batchNumber,
        UUID coveragePlanId,
        String coveragePlanName,
        ReprocessingBatchStatus status,
        int totalCount,
        int succeededCount,
        int failedCount,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {

    public static ReprocessingBatchSummaryDto from(ReprocessingBatch batch, String coveragePlanName) {
        return new ReprocessingBatchSummaryDto(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getCoveragePlanId(),
                coveragePlanName,
                batch.getStatus(),
                batch.getTotalCount(),
                batch.getSucceededCount(),
                batch.getFailedCount(),
                batch.getCreatedAt(),
                batch.getFinishedAt());
    }
}
