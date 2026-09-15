package com.healthcloud.coverage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A patient's enrollment in a {@link CoveragePlan} for an effective-dated period (source-of-truth §Phase 4).
 * Tenant-owned via {@code organizationId} and about a {@code patient} in the same organization, so access
 * inherits the patient object/relationship gate (§21 layer 6). {@code effectiveTo} is null for open-ended
 * coverage. Periods for a patient are kept non-overlapping (enforced in the service), so "coverage on a date"
 * resolves to at most one row — the deterministic input the Phase-5 adjudication engine needs.
 */
@Entity
@Table(name = "patient_eligibility")
public class PatientEligibility {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "coverage_plan_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID coveragePlanId;

    @Column(name = "member_id", nullable = false, length = 64)
    private String memberId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected PatientEligibility() {
        // for JPA
    }

    public PatientEligibility(UUID organizationId, UUID patientId, UUID coveragePlanId, String memberId,
                              LocalDate effectiveFrom, LocalDate effectiveTo, UUID createdBy) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.coveragePlanId = coveragePlanId;
        this.memberId = memberId;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdBy = createdBy;
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

    public UUID getCoveragePlanId() {
        return coveragePlanId;
    }

    public String getMemberId() {
        return memberId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public UUID getCreatedBy() {
        return createdBy;
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
