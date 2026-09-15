package com.healthcloud.clinical;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Payload to create a clinical summary. The tenant, patient (from the path) and author are taken from the
 * caller's context — never the client. {@code diagnosisCode} must be a real ICD-10-CM diagnosis in the global
 * catalog (validated in {@link ClinicalSummaryService}, returning a clean 400 otherwise). {@code narrative} is
 * optional (the consent-controlled clinical detail).
 */
public record ClinicalSummaryCreateRequest(
        @NotNull ClinicalSummaryType summaryType,
        @NotNull @PastOrPresent LocalDate encounterDate,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 16) String diagnosisCode,
        @Size(max = 4000) String narrative) {
}
