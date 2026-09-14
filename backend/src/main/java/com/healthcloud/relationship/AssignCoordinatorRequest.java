package com.healthcloud.relationship;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request to assign a care coordinator to a patient. {@code effectiveFrom} defaults to today when omitted; a
 * future date makes the assignment PENDING. The target must be a same-tenant CARE_COORDINATOR (validated on
 * the backend); the tenant and assigner are stamped from context.
 */
public record AssignCoordinatorRequest(
        @NotNull UUID coordinatorUserId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
