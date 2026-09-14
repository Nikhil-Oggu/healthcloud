package com.healthcloud.relationship;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of a provider-patient assignment. */
public record ProviderPatientAssignmentDto(
        UUID id,
        UUID patientId,
        UUID providerUserId,
        String providerName,
        UUID assignedByUserId,
        ProviderPatientAssignmentStatus status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        long expectedVersion,
        OffsetDateTime assignedAt,
        OffsetDateTime endedAt) {

    public static ProviderPatientAssignmentDto from(ProviderPatientAssignment a, String providerName) {
        return new ProviderPatientAssignmentDto(
                a.getId(), a.getPatientId(), a.getProviderUserId(), providerName, a.getAssignedByUserId(),
                a.getStatus(), a.getEffectiveFrom(), a.getEffectiveTo(), a.getVersion(),
                a.getAssignedAt(), a.getEndedAt());
    }
}
