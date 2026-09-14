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
 * The care relationship that puts a coordinator on a patient's care team (source-of-truth §14.3, §22).
 * Tenant-owned, effective-dated, and auditable: assignment and revocation are recorded, never deleted. At
 * most one CURRENT (ACTIVE/PENDING) assignment exists per (patient, coordinator) pair; revoking sets REVOKED
 * and stamps {@code endedAt}, freeing the pair to be re-assigned later. The organization and the assigner are
 * stamped from the backend context, never the client. Mirrors {@link ProviderPatientAssignment}.
 */
@Entity
@Table(name = "care_coordinator_assignment")
public class CareCoordinatorAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "coordinator_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID coordinatorUserId;

    @Column(name = "assigned_by_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID assignedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CareCoordinatorAssignmentStatus status;

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

    protected CareCoordinatorAssignment() {
        // for JPA
    }

    public CareCoordinatorAssignment(UUID organizationId, UUID patientId, UUID coordinatorUserId,
                                     UUID assignedByUserId, CareCoordinatorAssignmentStatus status,
                                     LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.coordinatorUserId = coordinatorUserId;
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
        this.status = CareCoordinatorAssignmentStatus.REVOKED;
        this.endedAt = OffsetDateTime.now();
    }

    /** Whether this assignment is in force or scheduled to be (i.e. not terminal history). */
    public boolean isCurrent() {
        return status == CareCoordinatorAssignmentStatus.ACTIVE
                || status == CareCoordinatorAssignmentStatus.PENDING;
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

    public UUID getCoordinatorUserId() {
        return coordinatorUserId;
    }

    public UUID getAssignedByUserId() {
        return assignedByUserId;
    }

    public CareCoordinatorAssignmentStatus getStatus() {
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
