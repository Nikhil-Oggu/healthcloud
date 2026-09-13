package com.healthcloud.request;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The assignment of a service request to a responsible user — a same-organization provider or claims
 * reviewer (source-of-truth §14.5, §32.4). Tenant-owned and versioned (§31.7). Reassignment supersedes
 * the current row (status → SUPERSEDED, {@code endedAt} stamped) and inserts a new ACTIVE row, so at
 * most one ACTIVE assignment exists per request while history is retained. The assignee, assigner, and
 * organization are stamped from the backend context, never the client.
 */
@Entity
@Table(name = "request_assignment")
public class RequestAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "service_request_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID serviceRequestId;

    @Column(name = "assignee_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID assigneeUserId;

    @Column(name = "assigned_by_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID assignedByUserId;

    @Column(name = "assignee_role", nullable = false, length = 40, updatable = false)
    private String assigneeRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestAssignmentStatus status = RequestAssignmentStatus.ACTIVE;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Version
    private long version;

    protected RequestAssignment() {
        // for JPA
    }

    public RequestAssignment(UUID organizationId, UUID serviceRequestId, UUID assigneeUserId,
                             UUID assignedByUserId, String assigneeRole) {
        this.organizationId = organizationId;
        this.serviceRequestId = serviceRequestId;
        this.assigneeUserId = assigneeUserId;
        this.assignedByUserId = assignedByUserId;
        this.assigneeRole = assigneeRole;
    }

    @PrePersist
    void onCreate() {
        this.assignedAt = OffsetDateTime.now();
    }

    /** Mark this assignment as superseded by a newer one. */
    public void supersede() {
        this.status = RequestAssignmentStatus.SUPERSEDED;
        this.endedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getServiceRequestId() {
        return serviceRequestId;
    }

    public UUID getAssigneeUserId() {
        return assigneeUserId;
    }

    public UUID getAssignedByUserId() {
        return assignedByUserId;
    }

    public String getAssigneeRole() {
        return assigneeRole;
    }

    public RequestAssignmentStatus getStatus() {
        return status;
    }

    public OffsetDateTime getAssignedAt() {
        return assignedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public long getVersion() {
        return version;
    }
}
