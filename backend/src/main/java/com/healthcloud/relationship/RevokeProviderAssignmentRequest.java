package com.healthcloud.relationship;

import jakarta.validation.constraints.NotNull;

/**
 * Request to revoke a current provider-patient assignment. {@code expectedVersion} is the optimistic-lock
 * value the caller last saw — a mismatch means someone else changed it → 409, and nothing is overwritten.
 */
public record RevokeProviderAssignmentRequest(
        @NotNull Long expectedVersion) {
}
