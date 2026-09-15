package com.healthcloud.coverage;

import java.math.BigDecimal;
import java.util.UUID;

/** Client-facing view of a coverage plan. */
public record CoveragePlanDto(
        UUID id,
        String planCode,
        String name,
        PlanType planType,
        BigDecimal deductibleAmount,
        BigDecimal coinsuranceRate,
        BigDecimal copayAmount,
        BigDecimal outOfPocketMax,
        boolean active,
        long version) {

    public static CoveragePlanDto from(CoveragePlan plan) {
        return new CoveragePlanDto(
                plan.getId(),
                plan.getPlanCode(),
                plan.getName(),
                plan.getPlanType(),
                plan.getDeductibleAmount(),
                plan.getCoinsuranceRate(),
                plan.getCopayAmount(),
                plan.getOutOfPocketMax(),
                plan.isActive(),
                plan.getVersion());
    }
}
