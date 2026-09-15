package com.healthcloud.claim;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One line of a create-claim request. {@code procedureCode} must be a real active PROCEDURE code (CPT or
 * HCPCS) in the global catalog — the system is resolved on the backend from the code (validated in
 * {@link ClaimService}, a clean 400 otherwise). {@code units} defaults to 1 when omitted.
 */
public record CreateClaimLineRequest(
        @NotBlank @Size(max = 16) String procedureCode,
        @Positive Integer units,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal chargeAmount) {
}
