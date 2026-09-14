package com.healthcloud.relationship;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The care relationship that controls a provider's access to a patient (source-of-truth §14.3, §32.4).
 * Tenant-owned, effective-dated, and auditable: assignment and revocation are recorded, never deleted. At
 * most one CURRENT (ACTIVE/PENDING) assignment exists per (patient, provider) pair; revoking sets REVOKED
 * and stamps {@code endedAt}, freeing the pair to be re-assigned later. The organization and the assigner
 * are stamped from the backend context, never the client.
 */
@Entity
@Table(name = "provider_patient_assignment")
public class ProviderPatientAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "provider_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID providerUserId;

    @Column(name = "assigned_by_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID assignedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderPatientAssignmentStatus status;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to", updatable = false)
    private LocalDate effectiveTo;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Version
    private long version;

    protected ProviderPatientAssignment() {
        // for JPA
    }

    public ProviderPatientAssignment(UUID organizationId, UUID patientId, UUID providerUserId,
                                     UUID assignedByUserId, ProviderPatientAssignmentStatus status,
                                     LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.providerUserId = providerUserId;
        this.assignedByUserId = assignedByUserId;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    @PrePersist
    void onCreate() {
        this.assignedAt = OffsetDateTime.now();
    }

    /** End this relationship with immediate effect (§14.3); the row is retained as history. */
    public void revoke() {
        this.status = ProviderPatientAssignmentStatus.REVOKED;
        this.endedAt = OffsetDateTime.now();
    }

    /** Whether this assignment is in force or scheduled to be (i.e. not terminal history). */
    public boolean isCurrent() {
        return status == ProviderPatientAssignmentStatus.ACTIVE
                || status == ProviderPatientAssignmentStatus.PENDING;
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

    public UUID getProviderUserId() {
        return providerUserId;
    }

    public UUID getAssignedByUserId() {
        return assignedByUserId;
    }

    public ProviderPatientAssignmentStatus getStatus() {
        return status;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public OffsetDateTime getAssignedAt() {
        return assignedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public long getVersion() {
        return version;
    }
}
