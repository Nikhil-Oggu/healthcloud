package com.healthcloud.adjudication;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Client-facing view of a claim adjudication — the header (which plan applied, the claim totals, the outcome)
 * plus the per-line breakdown. This is the §60 proof surface: for any decision it shows the plan that applied
 * and how every amount was computed. Coded, claim-relevant data only; not consent field-masked. Access is
 * controlled by the tenant + object/relationship gate (via the claim) at the service layer.
 */
public record AdjudicationDto(
        UUID id,
        UUID claimId,
        int adjudicationVersion,
        AdjudicationOutcome outcome,
        UUID coveragePlanId,
        String coveragePlanName,
        UUID eligibilityId,
        BigDecimal totalChargeAmount,
        BigDecimal totalAllowedAmount,
        BigDecimal totalPlanPaidAmount,
        BigDecimal totalMemberResponsibility,
        UUID adjudicatedBy,
        OffsetDateTime adjudicatedAt,
        List<AdjudicationLineDto> lines) {

    public static AdjudicationDto from(Adjudication adjudication, String coveragePlanName,
                                       List<AdjudicationLine> lines) {
        return new AdjudicationDto(
                adjudication.getId(),
                adjudication.getClaimId(),
                adjudication.getAdjudicationVersion(),
                adjudication.getOutcome(),
                adjudication.getCoveragePlanId(),
                coveragePlanName,
                adjudication.getEligibilityId(),
                adjudication.getTotalChargeAmount(),
                adjudication.getTotalAllowedAmount(),
                adjudication.getTotalPlanPaidAmount(),
                adjudication.getTotalMemberResponsibility(),
                adjudication.getAdjudicatedBy(),
                adjudication.getAdjudicatedAt(),
                lines.stream().map(AdjudicationLineDto::from).toList());
    }
}
