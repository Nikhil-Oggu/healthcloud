package com.healthcloud.coverage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload to enroll a patient in a coverage plan. The tenant, patient (from the path) and enroller are taken
 * from the caller's context. {@code coveragePlanId} must be a plan in the caller's tenant (else 400).
 * {@code effectiveTo} is optional (open-ended when omitted); the period must not overlap an existing one for
 * the patient (else 409).
 */
public record EnrollEligibilityRequest(
        @NotNull UUID coveragePlanId,
        @NotBlank @Size(max = 64) String memberId,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
