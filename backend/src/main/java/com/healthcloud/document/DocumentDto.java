package com.healthcloud.document;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of a patient document — <b>metadata only</b>. The file bytes are never carried in a DTO
 * (or a log or an event, §23.4); they are fetched separately from the download endpoint, which re-authorizes.
 */
public record DocumentDto(
        UUID id,
        UUID patientId,
        String fileName,
        String contentType,
        long sizeBytes,
        DocumentScanStatus scanStatus,
        UUID uploadedByUserId,
        OffsetDateTime uploadedAt) {

    public static DocumentDto from(PatientDocument d) {
        return new DocumentDto(
                d.getId(), d.getPatientId(), d.getFileName(), d.getContentType(), d.getSizeBytes(),
                d.getScanStatus(), d.getUploadedByUserId(), d.getUploadedAt());
    }
}
