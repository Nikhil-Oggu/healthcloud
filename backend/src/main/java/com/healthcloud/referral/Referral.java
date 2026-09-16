package com.healthcloud.referral;

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
 * A referral (source-of-truth §Phase 6, advanced claims) — a care-coordination request that a patient be seen by
 * a {@code specialty}, for a coded clinical {@code reason} (an ICD-10-CM diagnosis from the global catalog).
 * Tenant-owned via {@code organizationId} and about a {@code patient} in the same organization, so access
 * inherits the patient object/relationship gate (§21 layer 6). A care coordinator approves or denies it,
 * stamping {@code decidedBy}/{@code decidedAt}. Created in {@link ReferralStatus#REQUESTED}; controlled
 * transitions are driven by {@code ReferralTransitions}. Carries only coded, coordination-relevant data (the
 * reason is a diagnosis code, not free-text narrative), so it is not consent field-masked — a coordinator can
 * route it without unrestricted medical context.
 */
@Entity
@Table(name = "referral")
public class Referral {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "referral_number", nullable = false, length = 32, updatable = false)
    private String referralNumber;

    @Column(nullable = false, length = 100, updatable = false)
    private String specialty;

    @Column(name = "reason_code_system", nullable = false, length = 16, updatable = false)
    private String reasonCodeSystem;

    @Column(name = "reason_code", nullable = false, length = 16, updatable = false)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReferralStatus status = ReferralStatus.REQUESTED;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "decided_by", columnDefinition = "uuid")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "requested_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected Referral() {
        // for JPA
    }

    public Referral(UUID organizationId, UUID patientId, String referralNumber, String specialty,
                    String reasonCodeSystem, String reasonCode, UUID requestedBy) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.referralNumber = referralNumber;
        this.specialty = specialty;
        this.reasonCodeSystem = reasonCodeSystem;
        this.reasonCode = reasonCode;
        this.requestedBy = requestedBy;
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

    /** Record a coordinator's decision (APPROVED/DENIED): stamp who/when and the (optional) reason. */
    public void decide(ReferralStatus decision, UUID decidedBy, String reason) {
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

    public UUID getPatientId() {
        return patientId;
    }

    public String getReferralNumber() {
        return referralNumber;
    }

    public String getSpecialty() {
        return specialty;
    }

    public String getReasonCodeSystem() {
        return reasonCodeSystem;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public ReferralStatus getStatus() {
        return status;
    }

    public void setStatus(ReferralStatus status) {
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

    public UUID getRequestedBy() {
        return requestedBy;
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
