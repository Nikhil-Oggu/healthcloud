package com.healthcloud.referral;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to move a referral to {@code targetStatus} (approve/deny/cancel). {@code expectedVersion} is the
 * version the caller last saw (optimistic locking — mismatch → 409). {@code reason} is mandatory for some
 * transitions (deny/cancel), enforced in the service.
 */
public record ReferralStatusChangeRequest(
        @NotNull ReferralStatus targetStatus,
        @NotNull Long expectedVersion,
        @Size(max = 500) String reason) {
}
