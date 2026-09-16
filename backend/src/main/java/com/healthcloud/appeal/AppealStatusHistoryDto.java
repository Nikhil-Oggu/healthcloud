package com.healthcloud.appeal;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of one appeal status-history entry (the appeal timeline). */
public record AppealStatusHistoryDto(
        UUID id,
        AppealStatus fromStatus,
        AppealStatus toStatus,
        UUID actorUserId,
        String reason,
        OffsetDateTime createdAt) {

    public static AppealStatusHistoryDto from(AppealStatusHistory h) {
        return new AppealStatusHistoryDto(
                h.getId(), h.getFromStatus(), h.getToStatus(), h.getActorUserId(), h.getReason(), h.getCreatedAt());
    }
}
