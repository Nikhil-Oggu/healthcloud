package com.healthcloud.priorauth;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Header-only view of a prior authorization for list reads (a reviewer's work queue) — a single query, no
 * plan-name resolution.
 */
public record PriorAuthorizationSummaryDto(
        UUID id,
        UUID patientId,
        String authNumber,
        String procedureCodeSystem,
        String procedureCode,
        PriorAuthorizationStatus status,
        LocalDate requestedServiceFrom,
        OffsetDateTime createdAt) {

    public static PriorAuthorizationSummaryDto from(PriorAuthorization auth) {
        return new PriorAuthorizationSummaryDto(
                auth.getId(),
                auth.getPatientId(),
                auth.getAuthNumber(),
                auth.getProcedureCodeSystem(),
                auth.getProcedureCode(),
                auth.getStatus(),
                auth.getRequestedServiceFrom(),
                auth.getCreatedAt());
    }
}
