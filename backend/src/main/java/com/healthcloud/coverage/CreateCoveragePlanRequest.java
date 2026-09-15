package com.healthcloud.coverage;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Payload to create a coverage plan. The tenant is taken from the caller's context; {@code planCode} must be
 * unique within it (a clean 409 otherwise). {@code coinsuranceRate} is the member's share after the deductible
 * (0..1). {@code outOfPocketMax} is optional (no cap when omitted).
 */
public record CreateCoveragePlanRequest(
        @NotBlank @Size(max = 32) String planCode,
        @NotBlank @Size(max = 200) String name,
        @NotNull PlanType planType,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal deductibleAmount,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 4) BigDecimal coinsuranceRate,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal copayAmount,
        @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal outOfPocketMax) {
}
