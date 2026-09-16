package com.healthcloud.referral;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Header-only view of a referral for list reads (a coordinator's work queue) — a single query. */
public record ReferralSummaryDto(
        UUID id,
        UUID patientId,
        String referralNumber,
        String specialty,
        String reasonCodeSystem,
        String reasonCode,
        ReferralStatus status,
        OffsetDateTime createdAt) {

    public static ReferralSummaryDto from(Referral referral) {
        return new ReferralSummaryDto(
                referral.getId(),
                referral.getPatientId(),
                referral.getReferralNumber(),
                referral.getSpecialty(),
                referral.getReasonCodeSystem(),
                referral.getReasonCode(),
                referral.getStatus(),
                referral.getCreatedAt());
    }
}
