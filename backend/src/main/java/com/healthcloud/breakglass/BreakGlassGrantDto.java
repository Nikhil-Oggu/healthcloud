package com.healthcloud.breakglass;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of a break-glass grant. Carries the provider's own justification and the grant window; it is
 * returned to the granting provider (their own emergency access) and, in a later slice, to admins/auditors for
 * review. {@code active} is derived (now &lt; {@code expiresAt}) as a convenience for the UI.
 */
public record BreakGlassGrantDto(
        UUID id,
        UUID patientId,
        String reason,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        boolean active) {

    public static BreakGlassGrantDto from(BreakGlassGrant grant) {
        return new BreakGlassGrantDto(
                grant.getId(),
                grant.getPatientId(),
                grant.getReason(),
                grant.getCreatedAt(),
                grant.getExpiresAt(),
                grant.getExpiresAt().isAfter(OffsetDateTime.now()));
    }
}
