package com.healthcloud.request;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of one status-history entry (the request timeline). */
public record RequestStatusHistoryDto(
        UUID id,
        ServiceRequestStatus fromStatus,
        ServiceRequestStatus toStatus,
        UUID actorUserId,
        String reason,
        OffsetDateTime createdAt) {

    public static RequestStatusHistoryDto from(RequestStatusHistory h) {
        return new RequestStatusHistoryDto(
                h.getId(), h.getFromStatus(), h.getToStatus(), h.getActorUserId(), h.getReason(), h.getCreatedAt());
    }
}
