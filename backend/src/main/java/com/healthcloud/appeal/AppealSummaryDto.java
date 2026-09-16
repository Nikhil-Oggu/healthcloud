package com.healthcloud.appeal;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Header-only view of an appeal for list reads (a reviewer's work queue) — a single query. */
public record AppealSummaryDto(
        UUID id,
        UUID claimId,
        UUID patientId,
        String appealNumber,
        AppealStatus status,
        OffsetDateTime createdAt) {

    public static AppealSummaryDto from(Appeal appeal) {
        return new AppealSummaryDto(
                appeal.getId(),
                appeal.getClaimId(),
                appeal.getPatientId(),
                appeal.getAppealNumber(),
                appeal.getStatus(),
                appeal.getCreatedAt());
    }
}
