package com.healthcloud.breakglass;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload to break the glass for a patient. The provider names the {@code patientId} they must reach (often one
 * they cannot currently see) and records a required {@code reason} — the emergency justification. The tenant and
 * the acting provider are taken from the caller's context, never the client.
 */
public record CreateBreakGlassRequest(
        @NotNull UUID patientId,
        @NotBlank @Size(max = 500) String reason) {
}
