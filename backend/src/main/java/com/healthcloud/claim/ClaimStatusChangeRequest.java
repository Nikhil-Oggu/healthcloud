package com.healthcloud.claim;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to move a claim to {@code targetStatus}. {@code expectedVersion} is the version the caller last saw
 * (optimistic locking — mismatch → 409). {@code reason} is mandatory for some transitions (reject/cancel),
 * enforced in the service.
 */
public record ClaimStatusChangeRequest(
        @NotNull ClaimStatus targetStatus,
        @NotNull Long expectedVersion,
        @Size(max = 500) String reason) {
}
