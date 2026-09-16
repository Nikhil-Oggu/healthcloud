package com.healthcloud.claimreview;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to move a claim review to {@code targetStatus} (resolve/cancel). {@code expectedVersion} is the version
 * the caller last saw (optimistic locking — mismatch → 409). {@code reason} is mandatory on every transition
 * (enforced in the service) — on a resolve it is stored as the reviewer's conclusion.
 */
public record ClaimReviewStatusChangeRequest(
        @NotNull ClaimReviewStatus targetStatus,
        @NotNull Long expectedVersion,
        @Size(max = 500) String reason) {
}
