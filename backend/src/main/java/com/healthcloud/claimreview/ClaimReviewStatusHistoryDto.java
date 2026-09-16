package com.healthcloud.claimreview;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of one claim-review status-history entry (the review timeline). */
public record ClaimReviewStatusHistoryDto(
        UUID id,
        ClaimReviewStatus fromStatus,
        ClaimReviewStatus toStatus,
        UUID actorUserId,
        String reason,
        OffsetDateTime createdAt) {

    public static ClaimReviewStatusHistoryDto from(ClaimReviewStatusHistory h) {
        return new ClaimReviewStatusHistoryDto(
                h.getId(), h.getFromStatus(), h.getToStatus(), h.getActorUserId(), h.getReason(), h.getCreatedAt());
    }
}
