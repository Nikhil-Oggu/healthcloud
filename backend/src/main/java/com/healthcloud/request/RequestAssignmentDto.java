package com.healthcloud.request;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of the current assignment on a request. */
public record RequestAssignmentDto(
        UUID id,
        UUID assigneeUserId,
        String assigneeName,
        String assigneeRole,
        UUID assignedByUserId,
        OffsetDateTime assignedAt) {

    public static RequestAssignmentDto from(RequestAssignment a, String assigneeName) {
        return new RequestAssignmentDto(
                a.getId(), a.getAssigneeUserId(), assigneeName, a.getAssigneeRole(),
                a.getAssignedByUserId(), a.getAssignedAt());
    }
}
