package com.healthcloud.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Metadata for a patient document (source-of-truth §19). Tenant-owned; the file bytes are NOT stored here —
 * they live behind the {@link DocumentStorage} abstraction, addressed by {@link #storageKey}. The organization
 * and uploader are stamped from the backend context, never the client.
 *
 * <p>Access to a document inherits the patient object/relationship gate (§21 layer 6): every read/write routes
 * through {@code PatientAccessGuard.requireAccessibleInTenant(patientId)}. {@link #scanStatus} carries the
 * malware-scan lifecycle; this slice records {@link DocumentScanStatus#CLEAN} on upload.
 */
@Entity
@Table(name = "patient_document")
public class PatientDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 512, updatable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 20)
    private DocumentScanStatus scanStatus;

    @Column(name = "uploaded_by_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID uploadedByUserId;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected PatientDocument() {
        // for JPA
    }

    public PatientDocument(UUID organizationId, UUID patientId, String fileName, String contentType,
                           long sizeBytes, String storageKey, DocumentScanStatus scanStatus,
                           UUID uploadedByUserId) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.scanStatus = scanStatus;
        this.uploadedByUserId = uploadedByUserId;
    }

    @PrePersist
    void onCreate() {
        this.uploadedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public DocumentScanStatus getScanStatus() {
        return scanStatus;
    }

    public UUID getUploadedByUserId() {
        return uploadedByUserId;
    }

    public OffsetDateTime getUploadedAt() {
        return uploadedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
