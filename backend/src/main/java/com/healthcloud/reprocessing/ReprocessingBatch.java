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
 * A batch re-adjudication job (source-of-truth §Phase 6, advanced claims). Tenant-owned via
 * {@code organizationId}; its scope is a {@code coveragePlan} (administrative benefit config, not PHI) — every
 * already-ADJUDICATED claim currently on that plan is re-run through the (unchanged) adjudication engine, each
 * producing a new immutable adjudication version.
 *
 * <p>A batch is a <b>job record</b>, not a state machine: MVP runs it synchronously and it lands
 * {@link ReprocessingBatchStatus#COMPLETED} / {@link ReprocessingBatchStatus#COMPLETED_WITH_ERRORS}. There is no
 * client transition and no status history — the batch header (counts) plus its {@code reprocessing_item} children
 * are the record. Written twice within the run (a RUNNING header, then finalized), all inside the caller's
 * request thread but each save its own short transaction — the batch itself is not wrapped in one transaction, so
 * a single claim's failure never rolls back the batch or the other claims.
 */
@Entity
@Table(name = "reprocessing_batch")
public class ReprocessingBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "batch_number", nullable = false, length = 32, updatable = false)
    private String batchNumber;

    @Column(name = "coverage_plan_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID coveragePlanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReprocessingBatchStatus status = ReprocessingBatchStatus.RUNNING;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "succeeded_count", nullable = false)
    private int succeededCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "requested_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected ReprocessingBatch() {
        // for JPA
    }

    public ReprocessingBatch(UUID organizationId, String batchNumber, UUID coveragePlanId, UUID requestedBy) {
        this.organizationId = organizationId;
        this.batchNumber = batchNumber;
        this.coveragePlanId = coveragePlanId;
        this.requestedBy = requestedBy;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now();
        }
    }

    /** Record the batch result once the run is done: counts + the terminal status + the finish time. */
    public void finish(int totalCount, int succeededCount, int failedCount) {
        this.totalCount = totalCount;
        this.succeededCount = succeededCount;
        this.failedCount = failedCount;
        this.status = failedCount > 0
                ? ReprocessingBatchStatus.COMPLETED_WITH_ERRORS
                : ReprocessingBatchStatus.COMPLETED;
        this.finishedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public UUID getCoveragePlanId() {
        return coveragePlanId;
    }

    public ReprocessingBatchStatus getStatus() {
        return status;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getSucceededCount() {
        return succeededCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }
}
