package com.healthcloud.claim;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of one claim status-history entry (the claim timeline). */
public record ClaimStatusHistoryDto(
        UUID id,
        ClaimStatus fromStatus,
        ClaimStatus toStatus,
        UUID actorUserId,
        String reason,
        OffsetDateTime createdAt) {

    public static ClaimStatusHistoryDto from(ClaimStatusHistory h) {
        return new ClaimStatusHistoryDto(
                h.getId(), h.getFromStatus(), h.getToStatus(), h.getActorUserId(), h.getReason(), h.getCreatedAt());
    }
}
