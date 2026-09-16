package com.healthcloud.referral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload to request a referral. The tenant and requester are taken from the caller's context; the patient must
 * be one the caller can reach (else a secure 404). The reason must be a real active ICD-10-CM DIAGNOSIS code
 * (validated in the service → 400). {@code specialty} is the free-text target specialty (e.g. "Cardiology").
 */
public record CreateReferralRequest(
        @NotNull UUID patientId,
        @NotBlank @Size(max = 100) String specialty,
        @NotBlank @Size(max = 16) String reasonCode) {
}
