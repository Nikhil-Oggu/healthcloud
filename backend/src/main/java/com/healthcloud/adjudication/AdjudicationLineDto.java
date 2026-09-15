package com.healthcloud.adjudication;

import com.healthcloud.coding.CodeSystem;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Client-facing view of one adjudication line — the explainable breakdown of how the line was computed (§60:
 * how every amount was computed). Coded, claim-relevant data only; not consent field-masked.
 */
public record AdjudicationLineDto(
        UUID claimLineId,
        int lineNumber,
        CodeSystem procedureCodeSystem,
        String procedureCode,
        LineOutcome outcome,
        BigDecimal chargeAmount,
        BigDecimal allowedAmount,
        BigDecimal copayAmount,
        BigDecimal deductibleAppliedAmount,
        BigDecimal coinsuranceAmount,
        BigDecimal planPaidAmount,
        BigDecimal memberResponsibility) {

    public static AdjudicationLineDto from(AdjudicationLine line) {
        return new AdjudicationLineDto(
                line.getClaimLineId(),
                line.getLineNumber(),
                line.getProcedureCodeSystem(),
                line.getProcedureCode(),
                line.getOutcome(),
                line.getChargeAmount(),
                line.getAllowedAmount(),
                line.getCopayAmount(),
                line.getDeductibleAppliedAmount(),
                line.getCoinsuranceAmount(),
                line.getPlanPaidAmount(),
                line.getMemberResponsibility());
    }
}
