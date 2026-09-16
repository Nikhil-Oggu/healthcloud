package com.healthcloud.claimreview;

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
 * A manual-review case on a {@code claim} (source-of-truth §Phase 6, advanced claims). Tenant-owned via
 * {@code organizationId} and about a {@code claim} (with its {@code patientId} denormalized from that claim at
 * creation, never from the client), so access inherits the patient object/relationship gate (§21 layer 6). A
 * coordinator/reviewer opens it to flag a claim for a human look (often prompted by anomaly signals); a claims
 * reviewer resolves it, stamping {@code resolvedBy}/{@code resolvedAt} and the {@code resolution} conclusion.
 * Created {@link ClaimReviewStatus#OPEN}; controlled transitions are driven by {@code ClaimReviewTransitions}.
 *
 * <p>Carries only claims-domain data (why it was opened, the conclusion) — no clinical narrative — so it is not
 * consent field-masked. It is a <b>tracking</b> record: it neither holds the claim nor changes its status (the
 * reviewer uses the existing accept/reject/adjudicate actions).
 */
@Entity
@Table(name = "claim_review")
public class ClaimReview {

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

    @Column(name = "review_number", nullable = false, length = 32, updatable = false)
    private String reviewNumber;

    /** Why the review was opened (optional, immutable). */
    @Column(length = 1000, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimReviewStatus status = ClaimReviewStatus.OPEN;

    /** The reviewer's conclusion, set when the review is resolved. */
    @Column(length = 500)
    private String resolution;

    @Column(name = "opened_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID openedBy;

    @Column(name = "resolved_by", columnDefinition = "uuid")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected ClaimReview() {
        // for JPA
    }

    public ClaimReview(UUID organizationId, UUID claimId, UUID patientId, String reviewNumber, String reason,
                       UUID openedBy) {
        this.organizationId = organizationId;
        this.claimId = claimId;
        this.patientId = patientId;
        this.reviewNumber = reviewNumber;
        this.reason = reason;
        this.openedBy = openedBy;
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

    /** Record the reviewer's resolution (RESOLVED): stamp who/when and the conclusion. */
    public void resolve(UUID resolvedBy, String resolution) {
        this.status = ClaimReviewStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = OffsetDateTime.now();
        this.resolution = resolution;
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

    public String getReviewNumber() {
        return reviewNumber;
    }

    public String getReason() {
        return reason;
    }

    public ClaimReviewStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimReviewStatus status) {
        this.status = status;
    }

    public String getResolution() {
        return resolution;
    }

    public UUID getOpenedBy() {
        return openedBy;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
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
