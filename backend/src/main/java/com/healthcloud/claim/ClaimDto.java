package com.healthcloud.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Client-facing view of a claim aggregate — the header plus its lines. A claim carries only coded,
 * claim-relevant data (no clinical narrative), so it is not consent field-masked here; access is controlled by
 * the tenant + object/relationship gate at the service layer.
 */
public record ClaimDto(
        UUID id,
        UUID patientId,
        String claimNumber,
        ClaimStatus status,
        LocalDate serviceDate,
        BigDecimal totalChargeAmount,
        UUID renderingProviderId,
        UUID createdBy,
        OffsetDateTime createdAt,
        long version,
        List<ClaimLineDto> lines) {

    public static ClaimDto from(Claim claim, List<ClaimLine> lines) {
        return new ClaimDto(
                claim.getId(),
                claim.getPatientId(),
                claim.getClaimNumber(),
                claim.getStatus(),
                claim.getServiceDate(),
                claim.getTotalChargeAmount(),
                claim.getRenderingProviderId(),
                claim.getCreatedBy(),
                claim.getCreatedAt(),
                claim.getVersion(),
                lines.stream().map(ClaimLineDto::from).toList());
    }
}
