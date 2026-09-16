package com.healthcloud.claimreview;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of a claim review. Carries only claims-domain data (the reviewed claim, why it was opened,
 * the conclusion) — no clinical narrative — so it is not consent field-masked; access is controlled by the
 * tenant + object/relationship gate (via the review's patient) at the service layer.
 */
public record ClaimReviewDto(
        UUID id,
        UUID claimId,
        UUID patientId,
        String reviewNumber,
        String reason,
        ClaimReviewStatus status,
        String resolution,
        UUID openedBy,
        UUID resolvedBy,
        OffsetDateTime resolvedAt,
        OffsetDateTime createdAt,
        long version) {

    public static ClaimReviewDto from(ClaimReview review) {
        return new ClaimReviewDto(
                review.getId(),
                review.getClaimId(),
                review.getPatientId(),
                review.getReviewNumber(),
                review.getReason(),
                review.getStatus(),
                review.getResolution(),
                review.getOpenedBy(),
                review.getResolvedBy(),
                review.getResolvedAt(),
                review.getCreatedAt(),
                review.getVersion());
    }
}
