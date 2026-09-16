package com.healthcloud.breakglass;

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
 * One break-glass emergency-access grant (source-of-truth §Phase 7): a PROVIDER's time-boxed, self-declared
 * override of the object/relationship gate for a single patient, with a recorded {@code reason}. Tenant-owned via
 * {@code organizationId} and about a patient in the same organization. A live grant (now &lt; {@code expiresAt})
 * is consulted by {@code PatientAccessGuard} to let an otherwise-unassigned provider reach the patient.
 *
 * <p>A grant is append-only except for one allowed mutation — <b>early revocation</b> (§Phase 7 slice 6): an admin
 * reviewing standing emergency access can end it before {@code expiresAt} by stamping {@code revokedAt}/{@code
 * revokedBy}. A grant is <b>live</b> only when it is neither expired nor revoked (see {@link #isLive}); the access
 * guard and every active-grant query filter on both. The {@code reason} is the emergency justification kept for
 * after-the-fact review; it is NOT copied into the PHI-free audit-event detail (rule 5). Creating a grant writes a
 * BREAK_GLASS_INVOKED audit event, and revoking one a BREAK_GLASS_REVOKED event, each in the same transaction (§31.6).
 */
@Entity
@Table(name = "break_glass_grant")
public class BreakGlassGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "app_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID appUserId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    /** Set when an admin ends the grant early (null = not revoked). */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by", columnDefinition = "uuid")
    private UUID revokedBy;

    protected BreakGlassGrant() {
        // for JPA
    }

    public BreakGlassGrant(UUID organizationId, UUID appUserId, UUID patientId, String reason,
                           OffsetDateTime expiresAt) {
        this.organizationId = organizationId;
        this.appUserId = appUserId;
        this.patientId = patientId;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    /** Whether the grant is currently in force: neither expired nor revoked. */
    public boolean isLive() {
        return revokedAt == null && expiresAt.isAfter(OffsetDateTime.now());
    }

    /** End the grant early (the one allowed mutation), stamping who revoked it and when. */
    public void revoke(UUID revokedByUserId) {
        this.revokedAt = OffsetDateTime.now();
        this.revokedBy = revokedByUserId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAppUserId() {
        return appUserId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getReason() {
        return reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }
}
