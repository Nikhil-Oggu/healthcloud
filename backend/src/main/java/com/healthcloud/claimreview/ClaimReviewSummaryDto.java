package com.healthcloud.claimreview;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Header-only view of a claim review for list reads (a reviewer's work queue) — a single query. */
public record ClaimReviewSummaryDto(
        UUID id,
        UUID claimId,
        UUID patientId,
        String reviewNumber,
        ClaimReviewStatus status,
        OffsetDateTime createdAt) {

    public static ClaimReviewSummaryDto from(ClaimReview review) {
        return new ClaimReviewSummaryDto(
                review.getId(),
                review.getClaimId(),
                review.getPatientId(),
                review.getReviewNumber(),
                review.getStatus(),
                review.getCreatedAt());
    }
}
