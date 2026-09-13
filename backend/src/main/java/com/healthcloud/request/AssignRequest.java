package com.healthcloud.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request to assign (or reassign) a service request to {@code assigneeUserId}. {@code expectedVersion}
 * is the request version the caller last saw (optimistic locking — mismatch → 409). The tenant,
 * assigner, and the assignee's role are resolved on the backend.
 */
public record AssignRequest(
        @NotNull UUID assigneeUserId,
        @NotNull Long expectedVersion) {
}
