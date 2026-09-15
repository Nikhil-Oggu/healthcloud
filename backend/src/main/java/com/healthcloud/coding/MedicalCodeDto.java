package com.healthcloud.coding;

import java.util.UUID;

/**
 * Client-facing view of a medical code. Echoes the system's human-readable {@code systemLabel} and
 * {@code category} (Diagnosis/Procedure) so the UI can render a picker without hard-coding the enum.
 */
public record MedicalCodeDto(
        UUID id,
        CodeSystem codeSystem,
        String systemLabel,
        String category,
        String code,
        String description) {

    public static MedicalCodeDto from(MedicalCode c) {
        return new MedicalCodeDto(
                c.getId(), c.getCodeSystem(), c.getCodeSystem().label(), c.getCodeSystem().category(),
                c.getCode(), c.getDescription());
    }
}
