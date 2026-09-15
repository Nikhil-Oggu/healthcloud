package com.healthcloud.coverage;

import com.healthcloud.coding.CodeSystem;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Client-facing view of a fee-schedule entry — the priced procedure code and its allowed amount. Plan config,
 * not PHI; not consent-masked.
 */
public record PlanFeeScheduleDto(
        UUID id, UUID coveragePlanId, CodeSystem codeSystem, String code, BigDecimal allowedAmount) {

    public static PlanFeeScheduleDto from(PlanFeeScheduleEntry entry) {
        return new PlanFeeScheduleDto(
                entry.getId(),
                entry.getCoveragePlanId(),
                entry.getCodeSystem(),
                entry.getCode(),
                entry.getAllowedAmount());
    }
}
