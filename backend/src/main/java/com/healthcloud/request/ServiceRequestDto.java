package com.healthcloud.request;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of a service request. */
public record ServiceRequestDto(
        UUID id,
        UUID patientId,
        ServiceRequestType type,
        ServiceRequestStatus status,
        ServiceRequestPriority priority,
        String title,
        String description,
        UUID createdBy,
        long version,
        OffsetDateTime createdAt) {

    public static ServiceRequestDto from(ServiceRequest request) {
        return new ServiceRequestDto(
                request.getId(),
                request.getPatientId(),
                request.getType(),
                request.getStatus(),
                request.getPriority(),
                request.getTitle(),
                request.getDescription(),
                request.getCreatedBy(),
                request.getVersion(),
                request.getCreatedAt());
    }
}
