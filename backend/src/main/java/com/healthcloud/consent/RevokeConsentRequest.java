package com.healthcloud.consent;

import jakarta.validation.constraints.NotNull;

/**
 * Request to revoke a specific current consent directive. {@code expectedVersion} is the optimistic-lock
 * value (the directive's {@code expectedVersion} from its DTO) the caller last saw — a mismatch means
 * someone else changed it → 409, and nothing is overwritten.
 */
public record RevokeConsentRequest(
        @NotNull Long expectedVersion) {
}
