package com.healthcloud.claim;

import com.healthcloud.coding.CodeSystem;
import java.math.BigDecimal;
import java.util.UUID;

/** Client-facing view of one claim line. */
public record ClaimLineDto(
        UUID id,
        int lineNumber,
        CodeSystem procedureCodeSystem,
        String procedureCode,
        int units,
        BigDecimal chargeAmount) {

    public static ClaimLineDto from(ClaimLine line) {
        return new ClaimLineDto(
                line.getId(),
                line.getLineNumber(),
                line.getProcedureCodeSystem(),
                line.getProcedureCode(),
                line.getUnits(),
                line.getChargeAmount());
    }
}
