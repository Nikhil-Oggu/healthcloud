package com.healthcloud.request;

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
 * A comment on a service request — the collaboration thread (source-of-truth §14.5). Tenant-owned; a
 * comment belongs to a request in the same organization. Append-only from the API (no edit/delete in
 * this slice). The author and organization are stamped from the backend context, never the client.
 */
@Entity
@Table(name = "request_comment")
public class RequestComment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "service_request_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID serviceRequestId;

    @Column(name = "author_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID authorUserId;

    @Column(nullable = false, length = 2000, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RequestComment() {
        // for JPA
    }

    public RequestComment(UUID organizationId, UUID serviceRequestId, UUID authorUserId, String body) {
        this.organizationId = organizationId;
        this.serviceRequestId = serviceRequestId;
        this.authorUserId = authorUserId;
        this.body = body;
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

    public UUID getServiceRequestId() {
        return serviceRequestId;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public String getBody() {
        return body;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
