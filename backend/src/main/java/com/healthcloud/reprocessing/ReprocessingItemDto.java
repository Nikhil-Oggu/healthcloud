package com.healthcloud.reprocessing;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of one claim's result within a reprocessing batch. Claims-domain data only (the claim, the
 * outcome, the new version on success, a PHI-free message on failure) — not consent field-masked.
 */
public record ReprocessingItemDto(
        UUID id,
        UUID claimId,
        ReprocessingItemOutcome outcome,
        Integer adjudicationVersion,
        String message,
        OffsetDateTime createdAt) {

    public static ReprocessingItemDto from(ReprocessingItem item) {
        return new ReprocessingItemDto(
                item.getId(),
                item.getClaimId(),
                item.getOutcome(),
                item.getAdjudicationVersion(),
                item.getMessage(),
                item.getCreatedAt());
    }
}
