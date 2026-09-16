package com.healthcloud.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of an audit event. PHI-free metadata only (actor id + coded action/resource + outcome +
 * correlation id + a non-sensitive detail); not consent field-masked. Access is controlled by the tenant + role
 * gate (AUDITOR/ORG_ADMIN) at the service layer.
 */
public record AuditEventDto(
        UUID id,
        OffsetDateTime occurredAt,
        UUID actorUserId,
        AuditAction action,
        String resourceType,
        UUID resourceId,
        AuditOutcome outcome,
        String correlationId,
        String detail) {

    public static AuditEventDto from(AuditEvent event) {
        return new AuditEventDto(
                event.getId(),
                event.getOccurredAt(),
                event.getActorUserId(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getOutcome(),
                event.getCorrelationId(),
                event.getDetail());
    }
}
