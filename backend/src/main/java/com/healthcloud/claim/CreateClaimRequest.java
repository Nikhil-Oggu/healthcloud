package com.healthcloud.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload to create a claim. The tenant and creator are taken from the caller's context; the patient must be
 * one the caller can reach (else a secure 404). A claim needs at least one line. The header total is computed
 * on the backend from the lines — never supplied by the client. {@code renderingProviderId} (the provider who
 * rendered the service, §Phase 6 provider network) is optional; when present it must be an active same-tenant
 * PROVIDER (else 400).
 */
public record CreateClaimRequest(
        @NotNull UUID patientId,
        @NotNull @PastOrPresent LocalDate serviceDate,
        UUID renderingProviderId,
        @NotEmpty List<@Valid CreateClaimLineRequest> lines) {
}
