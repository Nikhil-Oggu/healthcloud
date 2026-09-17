package com.healthcloud.deadletter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One record that failed consumer processing and was parked on a dead-letter topic (source-of-truth §Phase 8
 * slice 5), drained here so it can be inspected (and later replayed) through ordinary tenant/role-gated APIs
 * instead of by consuming Kafka directly.
 *
 * <p>Tenant-owned via {@code organizationId} (from the original event's header) — nullable, since a fully
 * unattributable poison record may carry no tenant. Otherwise immutable, save for the one-way replay stamp
 * ({@code replayedAt}/{@code replayedBy}, slice 6). The DLT coordinates
 * ({@code dltTopic}/{@code dltPartition}/{@code dltOffset}) are unique so the drainer is idempotent. Payload and
 * exception text are claims-domain, PHI-free (rule 5).
 */
@Entity
@Table(name = "dead_letter_event")
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "source_topic", nullable = false, length = 128, updatable = false)
    private String sourceTopic;

    @Column(name = "message_key", length = 256, updatable = false)
    private String messageKey;

    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "event_id", columnDefinition = "uuid", updatable = false)
    private UUID eventId;

    @Column(name = "exception_type", nullable = false, length = 256, updatable = false)
    private String exceptionType;

    @Column(name = "exception_message", length = 1000, updatable = false)
    private String exceptionMessage;

    @Column(name = "dlt_topic", nullable = false, length = 128, updatable = false)
    private String dltTopic;

    @Column(name = "dlt_partition", nullable = false, updatable = false)
    private int dltPartition;

    @Column(name = "dlt_offset", nullable = false, updatable = false)
    private long dltOffset;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Replay lifecycle (slice 6): a one-way stamp on an otherwise-immutable row. Null replayedAt = not yet replayed.
    @Column(name = "replayed_at")
    private OffsetDateTime replayedAt;

    @Column(name = "replayed_by", columnDefinition = "uuid")
    private UUID replayedBy;

    protected DeadLetterEvent() {
        // for JPA
    }

    public DeadLetterEvent(UUID organizationId, String sourceTopic, String messageKey, String payload,
                           UUID eventId, String exceptionType, String exceptionMessage,
                           String dltTopic, int dltPartition, long dltOffset) {
        this.organizationId = organizationId;
        this.sourceTopic = sourceTopic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.eventId = eventId;
        this.exceptionType = exceptionType;
        this.exceptionMessage = exceptionMessage;
        this.dltTopic = dltTopic;
        this.dltPartition = dltPartition;
        this.dltOffset = dltOffset;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Mark this record as replayed (slice 6). A one-way stamp: it records who re-drove the message onto its source
     * topic and when. Called after a successful re-publish, inside the replay transaction.
     */
    public void markReplayed(UUID actorUserId) {
        this.replayedAt = OffsetDateTime.now();
        this.replayedBy = actorUserId;
    }

    /** Whether this record has already been replayed. */
    public boolean isReplayed() {
        return replayedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getSourceTopic() {
        return sourceTopic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public String getDltTopic() {
        return dltTopic;
    }

    public int getDltPartition() {
        return dltPartition;
    }

    public long getDltOffset() {
        return dltOffset;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getReplayedAt() {
        return replayedAt;
    }

    public UUID getReplayedBy() {
        return replayedBy;
    }
}
