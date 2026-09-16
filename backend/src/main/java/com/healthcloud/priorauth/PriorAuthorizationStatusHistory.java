package com.healthcloud.priorauth;

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
 * An immutable record of one status change on a prior authorization (source-of-truth §31.6): from-state,
 * to-state, actor, organization, reason, timestamp and correlation id. Append-only — never updated or deleted.
 * {@code fromStatus} is null for the creation row (null → REQUESTED). Mirrors {@code ClaimStatusHistory}.
 */
@Entity
@Table(name = "prior_authorization_status_history")
public class PriorAuthorizationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "prior_authorization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID priorAuthorizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private PriorAuthorizationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private PriorAuthorizationStatus toStatus;

    @Column(name = "actor_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID actorUserId;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PriorAuthorizationStatusHistory() {
        // for JPA
    }

    public PriorAuthorizationStatusHistory(UUID organizationId, UUID priorAuthorizationId,
                                           PriorAuthorizationStatus fromStatus, PriorAuthorizationStatus toStatus,
                                           UUID actorUserId, String reason, String correlationId) {
        this.organizationId = organizationId;
        this.priorAuthorizationId = priorAuthorizationId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.correlationId = correlationId;
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

    public UUID getPriorAuthorizationId() {
        return priorAuthorizationId;
    }

    public PriorAuthorizationStatus getFromStatus() {
        return fromStatus;
    }

    public PriorAuthorizationStatus getToStatus() {
        return toStatus;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
