package com.healthcloud.deadletter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The admin inspection view of a {@link DeadLetterEvent} (source-of-truth §Phase 8 slice 5). All fields are
 * claims-domain / operational metadata and PHI-free.
 */
public record DeadLetterEventDto(
        UUID id,
        UUID organizationId,
        String sourceTopic,
        String messageKey,
        String payload,
        UUID eventId,
        String exceptionType,
        String exceptionMessage,
        OffsetDateTime createdAt,
        OffsetDateTime replayedAt,
        UUID replayedBy) {

    static DeadLetterEventDto from(DeadLetterEvent event) {
        return new DeadLetterEventDto(
                event.getId(),
                event.getOrganizationId(),
                event.getSourceTopic(),
                event.getMessageKey(),
                event.getPayload(),
                event.getEventId(),
                event.getExceptionType(),
                event.getExceptionMessage(),
                event.getCreatedAt(),
                event.getReplayedAt(),
                event.getReplayedBy());
    }
}
