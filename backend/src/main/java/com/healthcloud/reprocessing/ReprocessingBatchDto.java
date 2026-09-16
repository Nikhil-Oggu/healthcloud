package com.healthcloud.reprocessing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Client-facing view of a reprocessing batch — the header (scope plan, status, counts) plus its per-claim items.
 * Claims/benefits-domain data only; not consent field-masked. Access is tenant-scoped (another tenant's batch is
 * a secure 404); the per-claim re-adjudications underneath were each fully patient-gated when they ran.
 */
public record ReprocessingBatchDto(
        UUID id,
        String batchNumber,
        UUID coveragePlanId,
        String coveragePlanName,
        ReprocessingBatchStatus status,
        int totalCount,
        int succeededCount,
        int failedCount,
        UUID requestedBy,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        List<ReprocessingItemDto> items) {

    public static ReprocessingBatchDto from(ReprocessingBatch batch, String coveragePlanName,
                                            List<ReprocessingItem> items) {
        return new ReprocessingBatchDto(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getCoveragePlanId(),
                coveragePlanName,
                batch.getStatus(),
                batch.getTotalCount(),
                batch.getSucceededCount(),
                batch.getFailedCount(),
                batch.getRequestedBy(),
                batch.getCreatedAt(),
                batch.getFinishedAt(),
                items.stream().map(ReprocessingItemDto::from).toList());
    }
}
