package com.healthcloud.priorauth;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of one prior-auth status-history entry (the authorization timeline). */
public record PriorAuthStatusHistoryDto(
        UUID id,
        PriorAuthorizationStatus fromStatus,
        PriorAuthorizationStatus toStatus,
        UUID actorUserId,
        String reason,
        OffsetDateTime createdAt) {

    public static PriorAuthStatusHistoryDto from(PriorAuthorizationStatusHistory h) {
        return new PriorAuthStatusHistoryDto(
                h.getId(), h.getFromStatus(), h.getToStatus(), h.getActorUserId(), h.getReason(), h.getCreatedAt());
    }
}
