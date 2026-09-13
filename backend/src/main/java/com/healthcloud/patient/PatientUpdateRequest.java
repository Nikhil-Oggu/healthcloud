package com.healthcloud.patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload to update a patient's mutable fields. {@code expectedVersion} is the row version the client
 * last saw — if it no longer matches, the update is rejected with 409 (optimistic locking), so a
 * concurrent edit is never silently overwritten.
 */
public record PatientUpdateRequest(
        @NotBlank @Size(max = 200) String fullName,
        @NotNull PatientStatus status,
        @NotNull Long expectedVersion) {
}
