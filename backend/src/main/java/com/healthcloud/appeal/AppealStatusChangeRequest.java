package com.healthcloud.appeal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to move an appeal to {@code targetStatus} (uphold/overturn/withdraw). {@code expectedVersion} is the
 * version the caller last saw (optimistic locking — mismatch → 409). {@code reason} is mandatory on every appeal
 * transition (enforced in the service).
 */
public record AppealStatusChangeRequest(
        @NotNull AppealStatus targetStatus,
        @NotNull Long expectedVersion,
        @Size(max = 500) String reason) {
}
