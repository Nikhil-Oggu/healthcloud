package com.healthcloud.priorauth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to move a prior authorization to {@code targetStatus} (approve/deny/cancel). {@code expectedVersion}
 * is the version the caller last saw (optimistic locking — mismatch → 409). {@code reason} is mandatory for some
 * transitions (deny/cancel), enforced in the service.
 */
public record PriorAuthStatusChangeRequest(
        @NotNull PriorAuthorizationStatus targetStatus,
        @NotNull Long expectedVersion,
        @Size(max = 500) String reason) {
}
