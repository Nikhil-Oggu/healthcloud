package com.healthcloud.reprocessing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One claim's result within a reprocessing batch (source-of-truth §Phase 6). Tenant-owned and about a
 * {@code claim}; it carries only claims-domain data — the claim id, the outcome, the new adjudication version on
 * success, and a PHI-free message on failure — so it is not consent field-masked. Immutable (written once).
 */
@Entity
@Table(name = "reprocessing_item")
public class ReprocessingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "reprocessing_batch_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID reprocessingBatchId;

    @Column(name = "claim_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ReprocessingItemOutcome outcome;

    /** The new adjudication version written on a successful re-adjudication (null on failure). */
    @Column(name = "adjudication_version")
    private Integer adjudicationVersion;

    /** A short, PHI-free reason on failure (null on success). */
    @Column(length = 500, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ReprocessingItem() {
        // for JPA
    }

    private ReprocessingItem(UUID organizationId, UUID reprocessingBatchId, UUID claimId,
                            ReprocessingItemOutcome outcome, Integer adjudicationVersion, String message) {
        this.organizationId = organizationId;
        this.reprocessingBatchId = reprocessingBatchId;
        this.claimId = claimId;
        this.outcome = outcome;
        this.adjudicationVersion = adjudicationVersion;
        this.message = message;
    }

    /** A claim re-adjudicated cleanly to a new version. */
    public static ReprocessingItem succeeded(UUID organizationId, UUID batchId, UUID claimId, int newVersion) {
        return new ReprocessingItem(
                organizationId, batchId, claimId, ReprocessingItemOutcome.SUCCEEDED, newVersion, null);
    }

    /** A claim whose re-adjudication threw; {@code message} is a short, PHI-free reason. */
    public static ReprocessingItem failed(UUID organizationId, UUID batchId, UUID claimId, String message) {
        return new ReprocessingItem(
                organizationId, batchId, claimId, ReprocessingItemOutcome.FAILED, null, message);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getReprocessingBatchId() {
        return reprocessingBatchId;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public ReprocessingItemOutcome getOutcome() {
        return outcome;
    }

    public Integer getAdjudicationVersion() {
        return adjudicationVersion;
    }

    public String getMessage() {
        return message;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
