package com.healthcloud.referral;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of a referral. Carries only coded, coordination-relevant data (a specialty, a coded reason
 * diagnosis, the decision) — no clinical narrative — so it is not consent field-masked; access is controlled by
 * the tenant + object/relationship gate at the service layer.
 */
public record ReferralDto(
        UUID id,
        UUID patientId,
        String referralNumber,
        String specialty,
        String reasonCodeSystem,
        String reasonCode,
        ReferralStatus status,
        String decisionReason,
        UUID decidedBy,
        OffsetDateTime decidedAt,
        UUID requestedBy,
        OffsetDateTime createdAt,
        long version) {

    public static ReferralDto from(Referral referral) {
        return new ReferralDto(
                referral.getId(),
                referral.getPatientId(),
                referral.getReferralNumber(),
                referral.getSpecialty(),
                referral.getReasonCodeSystem(),
                referral.getReasonCode(),
                referral.getStatus(),
                referral.getDecisionReason(),
                referral.getDecidedBy(),
                referral.getDecidedAt(),
                referral.getRequestedBy(),
                referral.getCreatedAt(),
                referral.getVersion());
    }
}
