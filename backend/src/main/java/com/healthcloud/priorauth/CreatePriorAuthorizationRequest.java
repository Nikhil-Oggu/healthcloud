package com.healthcloud.priorauth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload to request a prior authorization. The tenant and requester are taken from the caller's context; the
 * patient must be one the caller can reach (else a secure 404). The procedure must be a real active PROCEDURE
 * code (CPT/HCPCS) and the coverage plan must belong to the tenant — both validated in the service (→ 400). The
 * optional service-window end must not precede the start (service- and DB-enforced).
 */
public record CreatePriorAuthorizationRequest(
        @NotNull UUID patientId,
        @NotNull UUID coveragePlanId,
        @NotBlank String procedureCode,
        @NotNull LocalDate requestedServiceFrom,
        LocalDate requestedServiceTo) {
}
