package com.healthcloud.notification;

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
 * One notification produced by consuming a {@code claim.adjudicated} event (source-of-truth §Phase 8) — the read
 * side of the transactional-outbox pipeline. Built purely from the event (payload + headers), so this projection
 * never re-reads the claim. Tenant-owned via {@code organizationId} and immutable.
 *
 * <p><b>Idempotency:</b> {@code eventId} (the outbox event's id, carried in a Kafka header) is unique, so a
 * redelivered event — the relay is at-least-once — cannot create a second notification. The {@code message} carries
 * only claims/benefits data (claim number, amounts, outcome); no clinical narrative or patient identifier (rule 5).
 */
@Entity
@Table(name = "claim_adjudication_notification")
public class ClaimAdjudicationNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "claim_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID claimId;

    @Column(name = "event_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID eventId;

    @Column(nullable = false, length = 500, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ClaimAdjudicationNotification() {
        // for JPA
    }

    public ClaimAdjudicationNotification(UUID organizationId, UUID claimId, UUID eventId, String message) {
        this.organizationId = organizationId;
        this.claimId = claimId;
        this.eventId = eventId;
        this.message = message;
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

    public UUID getClaimId() {
        return claimId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getMessage() {
        return message;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
