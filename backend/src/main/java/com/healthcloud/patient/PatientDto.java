package com.healthcloud.patient;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Client-facing view of a patient. Field-level masking by consent/purpose is a Phase 3 concern; for
 * now the whole profile is returned to any authorized same-tenant caller.
 */
public record PatientDto(
        UUID id,
        String medicalRecordNumber,
        String fullName,
        LocalDate dateOfBirth,
        PatientStatus status) {

    public static PatientDto from(Patient patient) {
        return new PatientDto(
                patient.getId(),
                patient.getMedicalRecordNumber(),
                patient.getFullName(),
                patient.getDateOfBirth(),
                patient.getStatus());
    }
}
