package com.healthcloud.outbox;

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
 * One transactional-outbox row (source-of-truth §Phase 8): an integration event recorded in the <b>same
 * transaction</b> as the domain change that produced it (§31.6), so the change and the event commit atomically or
 * both roll back — the answer to the dual-write problem (a Kafka publish cannot join a DB transaction).
 *
 * <p>A row is written {@code published_at == null} (pending) and is <b>append-only except for one mutation</b> —
 * the relay stamps {@link #markPublished} after it has published the event to Kafka (a later Phase 8 slice). The
 * {@code payload} is a JSON document carrying <b>minimum-necessary, PHI-free</b> data only (rule 5): no clinical
 * narrative, no document bytes, no patient identifiers beyond an aggregate id. Tenant-owned via {@code
 * organizationId}; the {@code correlationId} ties the event back to the request that produced it.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    /** Set by the relay once the event has been published to Kafka (null = still pending). */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected OutboxEvent() {
        // for JPA
    }

    public OutboxEvent(UUID organizationId, String aggregateType, UUID aggregateId, String eventType,
                       String payload, String correlationId) {
        this.organizationId = organizationId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        this.occurredAt = OffsetDateTime.now();
    }

    /** Mark the event published (the one allowed mutation) — stamped by the relay after a successful Kafka send. */
    public void markPublished() {
        this.publishedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }
}
