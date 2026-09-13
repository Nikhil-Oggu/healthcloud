package com.healthcloud.consent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of a consent directive. Exposes the domain {@code version} and the optimistic-lock
 * {@code expectedVersion} (the JPA lock counter) so the client can send it back on a revoke.
 */
public record ConsentDirectiveDto(
        UUID id,
        UUID patientId,
        UUID directiveGroupId,
        ConsentEffect effect,
        ConsentPurpose purpose,
        ConsentDataCategory dataCategory,
        ConsentScopeType scopeType,
        UUID scopeRefId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        ConsentStatus status,
        int version,
        long expectedVersion,
        OffsetDateTime createdAt,
        OffsetDateTime endedAt) {

    public static ConsentDirectiveDto from(ConsentDirective d) {
        return new ConsentDirectiveDto(
                d.getId(), d.getPatientId(), d.getDirectiveGroupId(), d.getEffect(), d.getPurpose(),
                d.getDataCategory(), d.getScopeType(), d.getScopeRefId(), d.getEffectiveFrom(),
                d.getEffectiveTo(), d.getStatus(), d.getVersion(), d.getLockVersion(),
                d.getCreatedAt(), d.getEndedAt());
    }
}
