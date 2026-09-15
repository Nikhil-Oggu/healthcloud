package com.healthcloud.clinical;

import com.healthcloud.coding.CodeSystem;
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
 * A short clinical note about a patient's encounter (source-of-truth §Phase 4). Tenant-owned via
 * {@code organizationId} and always about a {@code patient} in the same organization, so access inherits the
 * patient object/relationship gate (§21 layer 6). The {@code diagnosisCodeSystem}/{@code diagnosisCode} point
 * at an entry in the global medical code catalog (validated at write time); the free-text {@code narrative} is
 * the consent-controlled sensitive field (§22.5/§23 — masked on read for actors without consent).
 */
@Entity
@Table(name = "clinical_summary")
public class ClinicalSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_type", nullable = false, length = 20)
    private ClinicalSummaryType summaryType;

    @Column(name = "encounter_date", nullable = false)
    private LocalDate encounterDate;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "diagnosis_code_system", nullable = false, length = 16)
    private CodeSystem diagnosisCodeSystem;

    @Column(name = "diagnosis_code", nullable = false, length = 16)
    private String diagnosisCode;

    @Column(length = 4000)
    private String narrative;

    @Column(name = "author_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID authorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long version;

    protected ClinicalSummary() {
        // for JPA
    }

    public ClinicalSummary(UUID organizationId, UUID patientId, ClinicalSummaryType summaryType,
                           LocalDate encounterDate, String title, CodeSystem diagnosisCodeSystem,
                           String diagnosisCode, String narrative, UUID authorUserId) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.summaryType = summaryType;
        this.encounterDate = encounterDate;
        this.title = title;
        this.diagnosisCodeSystem = diagnosisCodeSystem;
        this.diagnosisCode = diagnosisCode;
        this.narrative = narrative;
        this.authorUserId = authorUserId;
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

    public UUID getPatientId() {
        return patientId;
    }

    public ClinicalSummaryType getSummaryType() {
        return summaryType;
    }

    public LocalDate getEncounterDate() {
        return encounterDate;
    }

    public String getTitle() {
        return title;
    }

    public CodeSystem getDiagnosisCodeSystem() {
        return diagnosisCodeSystem;
    }

    public String getDiagnosisCode() {
        return diagnosisCode;
    }

    public String getNarrative() {
        return narrative;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
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
