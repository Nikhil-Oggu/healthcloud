package com.healthcloud.breakglass;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The access-review view of a live break-glass grant (source-of-truth §Phase 7 slice 6). Unlike the provider's own
 * {@link BreakGlassGrantDto}, it names the acting provider (id + resolved name) so an admin/auditor can review who
 * holds emergency access to whom, and why. Returned only to AUDITOR/ORG_ADMIN.
 */
public record BreakGlassGrantAdminDto(
        UUID id,
        UUID providerUserId,
        String providerName,
        UUID patientId,
        String reason,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {

    public static BreakGlassGrantAdminDto from(BreakGlassGrant grant, String providerName) {
        return new BreakGlassGrantAdminDto(
                grant.getId(),
                grant.getAppUserId(),
                providerName,
                grant.getPatientId(),
                grant.getReason(),
                grant.getCreatedAt(),
                grant.getExpiresAt());
    }
}
