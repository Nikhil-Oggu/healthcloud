package com.healthcloud.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Header-only view of a claim for list reads (a reviewer's work queue) — no lines, so the listing is a single
 * query. The header's {@code totalChargeAmount} already summarizes the lines.
 */
public record ClaimSummaryDto(
        UUID id,
        UUID patientId,
        String claimNumber,
        ClaimStatus status,
        LocalDate serviceDate,
        BigDecimal totalChargeAmount,
        OffsetDateTime createdAt) {

    public static ClaimSummaryDto from(Claim claim) {
        return new ClaimSummaryDto(
                claim.getId(),
                claim.getPatientId(),
                claim.getClaimNumber(),
                claim.getStatus(),
                claim.getServiceDate(),
                claim.getTotalChargeAmount(),
                claim.getCreatedAt());
    }
}
