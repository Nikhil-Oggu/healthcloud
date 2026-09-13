package com.healthcloud.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to move a service request to {@code targetStatus}. {@code expectedVersion} is the version
 * the caller last saw (optimistic locking — mismatch → 409). {@code reason} is mandatory for some
 * transitions (cancel/reject), enforced in the service.
 */
public record StatusChangeRequest(
        @NotNull ServiceRequestStatus targetStatus,
        @NotNull Long expectedVersion,
        @Size(max = 500) String reason) {
}
