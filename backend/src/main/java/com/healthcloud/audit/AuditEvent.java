package com.healthcloud.audit;

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
 * One security audit event (source-of-truth §Phase 7). An append-only record that a security-relevant action
 * happened: an {@code actor} took an {@code action} on a resource ({@code resourceType} + {@code resourceId}),
 * in a tenant ({@code organizationId}), at {@code occurredAt}, with an {@code outcome}, tied to the request's
 * {@code correlationId}.
 *
 * <p>Rows are <b>immutable and append-only</b> — an audit trail is never edited or deleted in-app — so there is no
 * version column and every field is {@code updatable = false}. The row is written <b>inside the domain action's own
 * transaction</b> (§31.6) by {@link AuditService}, so it commits atomically with the change it records. It carries
 * only PHI-free metadata (a coded action/resource + a short, non-sensitive {@code detail}), never a clinical
 * narrative or patient identifier (rule 5), so it is not consent field-masked. This table is the foundation the
 * later Phase-7 slices extend (the tamper-evident HMAC chain, break-glass, retention).
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    /** Nullable: a system or unauthenticated action has no acting user. */
    @Column(name = "actor_user_id", columnDefinition = "uuid", updatable = false)
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60, updatable = false)
    private AuditAction action;

    @Column(name = "resource_type", nullable = false, length = 40, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", columnDefinition = "uuid", updatable = false)
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AuditOutcome outcome;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(nullable = false, length = 500, updatable = false)
    private String detail;

    protected AuditEvent() {
        // for JPA
    }

    public AuditEvent(UUID organizationId, UUID actorUserId, AuditAction action, String resourceType,
                      UUID resourceId, AuditOutcome outcome, String correlationId, String detail) {
        this.organizationId = organizationId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.detail = detail;
    }

    @PrePersist
    void onCreate() {
        this.occurredAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getDetail() {
        return detail;
    }
}
