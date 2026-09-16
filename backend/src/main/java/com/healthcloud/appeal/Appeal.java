package com.healthcloud.appeal;

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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An appeal (source-of-truth §Phase 6, advanced claims) — a dispute of a {@code claim}'s decision. Tenant-owned
 * via {@code organizationId} and about a {@code claim} (with its {@code patientId} denormalized from that claim
 * at creation, never from the client), so access inherits the patient object/relationship gate (§21 layer 6). A
 * claims reviewer upholds or overturns it, stamping {@code decidedBy}/{@code decidedAt}. Created in
 * {@link AppealStatus#SUBMITTED}; controlled transitions are driven by {@code AppealTransitions}. Carries only
 * claims-domain data (the dispute rationale, not clinical narrative), so — like a claim — it is not consent
 * field-masked.
 */
@Entity
@Table(name = "appeal")
public class Appeal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "claim_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID claimId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "appeal_number", nullable = false, length = 32, updatable = false)
    private String appealNumber;

    @Column(nullable = false, length = 1000, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppealStatus status = AppealStatus.SUBMITTED;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "decided_by", columnDefinition = "uuid")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "submitted_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID submittedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected Appeal() {
        // for JPA
    }

    public Appeal(UUID organizationId, UUID claimId, UUID patientId, String appealNumber, String reason,
                  UUID submittedBy) {
        this.organizationId = organizationId;
        this.claimId = claimId;
        this.patientId = patientId;
        this.appealNumber = appealNumber;
        this.reason = reason;
        this.submittedBy = submittedBy;
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

    /** Record a reviewer's decision (UPHELD/OVERTURNED): stamp who/when and the reason. */
    public void decide(AppealStatus decision, UUID decidedBy, String reason) {
        this.status = decision;
        this.decidedBy = decidedBy;
        this.decidedAt = OffsetDateTime.now();
        this.decisionReason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getAppealNumber() {
        return appealNumber;
    }

    public String getReason() {
        return reason;
    }

    public AppealStatus getStatus() {
        return status;
    }

    public void setStatus(AppealStatus status) {
        this.status = status;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public UUID getSubmittedBy() {
        return submittedBy;
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
