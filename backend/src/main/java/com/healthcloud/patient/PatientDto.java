package com.healthcloud.patient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Client-facing view of a patient. Reads are <b>field-safe</b> (source-of-truth §23.3): a consent-controlled
 * field the caller may not see is returned as {@code null} and named in {@code maskedFields}, so the client
 * can show "restricted" without ever receiving the value. Write responses (create/update) are unmasked — the
 * caller just supplied the data — via {@link #from(Patient)} (empty {@code maskedFields}).
 */
public record PatientDto(
        UUID id,
        String medicalRecordNumber,
        String fullName,
        LocalDate dateOfBirth,
        PatientStatus status,
        long version,
        List<String> maskedFields) {

    /** Unmasked view (write responses): every field present, nothing masked. */
    public static PatientDto from(Patient patient) {
        return new PatientDto(
                patient.getId(),
                patient.getMedicalRecordNumber(),
                patient.getFullName(),
                patient.getDateOfBirth(),
                patient.getStatus(),
                patient.getVersion(), // clients send this back as expectedVersion on update (optimistic lock)
                List.of());
    }

    /**
     * Field-safe read view: any field name in {@code maskedFieldNames} is blanked in the response and listed
     * in {@code maskedFields}. Currently only {@code dateOfBirth} is consent-controlled (see
     * {@link PatientFieldPolicy}).
     */
    public static PatientDto masked(Patient patient, List<String> maskedFieldNames) {
        boolean maskDob = maskedFieldNames.contains("dateOfBirth");
        return new PatientDto(
                patient.getId(),
                patient.getMedicalRecordNumber(),
                patient.getFullName(),
                maskDob ? null : patient.getDateOfBirth(),
                patient.getStatus(),
                patient.getVersion(),
                List.copyOf(maskedFieldNames));
    }
}
