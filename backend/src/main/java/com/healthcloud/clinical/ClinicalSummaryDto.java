package com.healthcloud.clinical;

import com.healthcloud.coding.CodeSystem;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Client-facing view of a clinical summary. Reads are <b>field-safe</b> (source-of-truth §23.3): the
 * consent-controlled {@code narrative} the caller may not see is returned as {@code null} and named in
 * {@code maskedFields}, while the structured diagnosis code stays visible. Write responses (create) are
 * unmasked — the caller just supplied the data — via {@link #from(ClinicalSummary)} (empty {@code maskedFields}).
 */
public record ClinicalSummaryDto(
        UUID id,
        UUID patientId,
        ClinicalSummaryType summaryType,
        LocalDate encounterDate,
        String title,
        CodeSystem diagnosisCodeSystem,
        String diagnosisCode,
        String narrative,
        UUID authorUserId,
        OffsetDateTime createdAt,
        long version,
        List<String> maskedFields) {

    /** Unmasked view (write responses): every field present, nothing masked. */
    public static ClinicalSummaryDto from(ClinicalSummary s) {
        return new ClinicalSummaryDto(
                s.getId(),
                s.getPatientId(),
                s.getSummaryType(),
                s.getEncounterDate(),
                s.getTitle(),
                s.getDiagnosisCodeSystem(),
                s.getDiagnosisCode(),
                s.getNarrative(),
                s.getAuthorUserId(),
                s.getCreatedAt(),
                s.getVersion(),
                List.of());
    }

    /**
     * Field-safe read view: any field name in {@code maskedFieldNames} is blanked in the response and listed
     * in {@code maskedFields}. Currently only {@code narrative} is consent-controlled (see
     * {@link ClinicalSummaryFieldPolicy}).
     */
    public static ClinicalSummaryDto masked(ClinicalSummary s, List<String> maskedFieldNames) {
        boolean maskNarrative = maskedFieldNames.contains("narrative");
        return new ClinicalSummaryDto(
                s.getId(),
                s.getPatientId(),
                s.getSummaryType(),
                s.getEncounterDate(),
                s.getTitle(),
                s.getDiagnosisCodeSystem(),
                s.getDiagnosisCode(),
                maskNarrative ? null : s.getNarrative(),
                s.getAuthorUserId(),
                s.getCreatedAt(),
                s.getVersion(),
                List.copyOf(maskedFieldNames));
    }
}
