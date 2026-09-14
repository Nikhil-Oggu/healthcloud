package com.healthcloud.relationship;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of a care-coordinator↔patient assignment. */
public record CareCoordinatorAssignmentDto(
        UUID id,
        UUID patientId,
        UUID coordinatorUserId,
        String coordinatorName,
        UUID assignedByUserId,
        CareCoordinatorAssignmentStatus status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        long expectedVersion,
        OffsetDateTime assignedAt,
        OffsetDateTime endedAt) {

    public static CareCoordinatorAssignmentDto from(CareCoordinatorAssignment a, String coordinatorName) {
        return new CareCoordinatorAssignmentDto(
                a.getId(), a.getPatientId(), a.getCoordinatorUserId(), coordinatorName, a.getAssignedByUserId(),
                a.getStatus(), a.getEffectiveFrom(), a.getEffectiveTo(), a.getVersion(),
                a.getAssignedAt(), a.getEndedAt());
    }
}
