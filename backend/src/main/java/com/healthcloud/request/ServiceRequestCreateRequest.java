package com.healthcloud.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload to create a service request. The tenant and creator are taken from the caller's context;
 * the patient must belong to that same tenant. {@code priority} is optional (defaults to NORMAL).
 */
public record ServiceRequestCreateRequest(
        @NotNull UUID patientId,
        @NotNull ServiceRequestType type,
        ServiceRequestPriority priority,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description) {
}
