package com.healthcloud.coverage;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Payload to price a procedure on a coverage plan. The {@code procedureCode} is resolved and validated against
 * the global medical code catalog (CPT/HCPCS); an unknown or non-procedure code is a 400. The {@code allowedAmount}
 * is the amount the plan will recognize (money, ≥ 0). The plan and tenant come from the path/context, never the
 * client.
 */
public record AddFeeScheduleRequest(
        @NotBlank @Size(max = 16) String procedureCode,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal allowedAmount) {
}
