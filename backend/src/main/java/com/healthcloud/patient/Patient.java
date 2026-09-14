package com.healthcloud.patient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A synthetic patient profile (source-of-truth §32.9). Tenant-owned: {@code organizationId} is the
 * tenant key and is held as a plain UUID (the tenant boundary), not as a JPA association — patient
 * reads never need the {@code Organization} object, and every query is scoped by this id. A patient
 * belongs to exactly one organization.
 */
@Entity
@Table(name = "patient")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "medical_record_number", nullable = false, length = 32)
    private String medicalRecordNumber;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    /**
     * The login (app_user) that IS this patient, or null when the profile has no portal user. When set, a
     * PATIENT-role user may reach only the profile linked to them (§21 layer 6, enforced in PatientAccessGuard).
     */
    @Column(name = "app_user_id", columnDefinition = "uuid")
    private UUID appUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PatientStatus status = PatientStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected Patient() {
        // for JPA
    }

    public Patient(UUID organizationId, String medicalRecordNumber, String fullName, LocalDate dateOfBirth) {
        this.organizationId = organizationId;
        this.medicalRecordNumber = medicalRecordNumber;
        this.fullName = fullName;
        this.dateOfBirth = dateOfBirth;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getMedicalRecordNumber() {
        return medicalRecordNumber;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public UUID getAppUserId() {
        return appUserId;
    }

    /** Link (or unlink) the login that IS this patient. Used by the seeder / patient-onboarding. */
    public void setAppUserId(UUID appUserId) {
        this.appUserId = appUserId;
    }

    public PatientStatus getStatus() {
        return status;
    }

    public void setStatus(PatientStatus status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
