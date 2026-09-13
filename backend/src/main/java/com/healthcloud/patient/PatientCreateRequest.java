package com.healthcloud.patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Payload to create a patient. The tenant is NOT part of the request — it is stamped on the server
 * from the caller's context, so a client can never create a patient in another organization.
 */
public record PatientCreateRequest(
        @NotBlank @Size(max = 32) String medicalRecordNumber,
        @NotBlank @Size(max = 200) String fullName,
        @NotNull @Past LocalDate dateOfBirth) {
}
