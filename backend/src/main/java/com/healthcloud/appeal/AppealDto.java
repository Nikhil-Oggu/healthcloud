package com.healthcloud.appeal;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of an appeal. Carries only claims-domain data (the disputed claim, the rationale, the
 * decision) — no clinical narrative — so it is not consent field-masked; access is controlled by the tenant +
 * object/relationship gate (via the appeal's patient) at the service layer.
 */
public record AppealDto(
        UUID id,
        UUID claimId,
        UUID patientId,
        String appealNumber,
        String reason,
        AppealStatus status,
        String decisionReason,
        UUID decidedBy,
        OffsetDateTime decidedAt,
        UUID submittedBy,
        OffsetDateTime createdAt,
        long version) {

    public static AppealDto from(Appeal appeal) {
        return new AppealDto(
                appeal.getId(),
                appeal.getClaimId(),
                appeal.getPatientId(),
                appeal.getAppealNumber(),
                appeal.getReason(),
                appeal.getStatus(),
                appeal.getDecisionReason(),
                appeal.getDecidedBy(),
                appeal.getDecidedAt(),
                appeal.getSubmittedBy(),
                appeal.getCreatedAt(),
                appeal.getVersion());
    }
}
