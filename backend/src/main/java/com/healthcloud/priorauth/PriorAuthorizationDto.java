package com.healthcloud.priorauth;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of a prior authorization. Carries only coded, claim-relevant data (a procedure code, a
 * service window, the coverage plan, the decision) — no clinical narrative — so it is not consent field-masked;
 * access is controlled by the tenant + object/relationship gate at the service layer. {@code coveragePlanName}
 * is resolved at read for convenience.
 */
public record PriorAuthorizationDto(
        UUID id,
        UUID patientId,
        String authNumber,
        UUID coveragePlanId,
        String coveragePlanName,
        String procedureCodeSystem,
        String procedureCode,
        LocalDate requestedServiceFrom,
        LocalDate requestedServiceTo,
        PriorAuthorizationStatus status,
        String decisionReason,
        UUID decidedBy,
        OffsetDateTime decidedAt,
        UUID requestedBy,
        OffsetDateTime createdAt,
        long version) {

    public static PriorAuthorizationDto from(PriorAuthorization auth, String coveragePlanName) {
        return new PriorAuthorizationDto(
                auth.getId(),
                auth.getPatientId(),
                auth.getAuthNumber(),
                auth.getCoveragePlanId(),
                coveragePlanName,
                auth.getProcedureCodeSystem(),
                auth.getProcedureCode(),
                auth.getRequestedServiceFrom(),
                auth.getRequestedServiceTo(),
                auth.getStatus(),
                auth.getDecisionReason(),
                auth.getDecidedBy(),
                auth.getDecidedAt(),
                auth.getRequestedBy(),
                auth.getCreatedAt(),
                auth.getVersion());
    }
}
