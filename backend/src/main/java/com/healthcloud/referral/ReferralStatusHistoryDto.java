package com.healthcloud.referral;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of one referral status-history entry (the referral timeline). */
public record ReferralStatusHistoryDto(
        UUID id,
        ReferralStatus fromStatus,
        ReferralStatus toStatus,
        UUID actorUserId,
        String reason,
        OffsetDateTime createdAt) {

    public static ReferralStatusHistoryDto from(ReferralStatusHistory h) {
        return new ReferralStatusHistoryDto(
                h.getId(), h.getFromStatus(), h.getToStatus(), h.getActorUserId(), h.getReason(), h.getCreatedAt());
    }
}
