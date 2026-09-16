package com.healthcloud.appeal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload to submit an appeal against a claim. The tenant and submitter are taken from the caller's context; the
 * claim must be one the caller can reach (else a secure 404) and in an appealable state (ADJUDICATED/REJECTED —
 * validated in the service, else 400). The patient is derived from the claim, never the client.
 */
public record CreateAppealRequest(
        @NotNull UUID claimId,
        @NotBlank @Size(max = 1000) String reason) {
}
