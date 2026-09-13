package com.healthcloud.consent;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A patient's recorded consent decision (source-of-truth §22). Tenant-owned, immutable, and versioned
 * (§22.4): an activated directive is never edited in place. "Changing" it {@link #supersede()}s the current
 * row and inserts a new version in the same {@code directiveGroupId}; revocation {@link #revoke()}s it. At
 * most one CURRENT (ACTIVE or SCHEDULED) directive exists per logical natural key — enforced by a partial
 * unique index. The organization and author are stamped from the backend context, never the client.
 *
 * <p>Two version numbers live here on purpose: {@code version} is the domain version of the directive
 * within its group (1, 2, 3…); {@code lockVersion} is the JPA optimistic-lock counter (§31 concurrency).
 */
@Entity
@Table(name = "consent_directive")
public class ConsentDirective {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "directive_group_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID directiveGroupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private ConsentEffect effect;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private ConsentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_category", nullable = false, length = 30, updatable = false)
    private ConsentDataCategory dataCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20, updatable = false)
    private ConsentScopeType scopeType;

    @Column(name = "scope_ref_id", columnDefinition = "uuid", updatable = false)
    private UUID scopeRefId;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to", updatable = false)
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsentStatus status;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected ConsentDirective() {
        // for JPA
    }

    public ConsentDirective(UUID organizationId, UUID patientId, UUID directiveGroupId, ConsentEffect effect,
                            ConsentPurpose purpose, ConsentDataCategory dataCategory, ConsentScopeType scopeType,
                            UUID scopeRefId, LocalDate effectiveFrom, LocalDate effectiveTo,
                            ConsentStatus status, int version, UUID createdBy) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.directiveGroupId = directiveGroupId;
        this.effect = effect;
        this.purpose = purpose;
        this.dataCategory = dataCategory;
        this.scopeType = scopeType;
        this.scopeRefId = scopeRefId;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.status = status;
        this.version = version;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    /** Mark this directive as superseded by a newer version (a modification was recorded). */
    public void supersede() {
        this.status = ConsentStatus.SUPERSEDED;
        this.endedAt = OffsetDateTime.now();
    }

    /** Revoke this directive with immediate effect (§22.4); prior history is retained. */
    public void revoke() {
        this.status = ConsentStatus.REVOKED;
        this.endedAt = OffsetDateTime.now();
    }

    /** Whether this directive is currently in force or scheduled to be (i.e. not terminal history). */
    public boolean isCurrent() {
        return status == ConsentStatus.ACTIVE || status == ConsentStatus.SCHEDULED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public UUID getDirectiveGroupId() {
        return directiveGroupId;
    }

    public ConsentEffect getEffect() {
        return effect;
    }

    public ConsentPurpose getPurpose() {
        return purpose;
    }

    public ConsentDataCategory getDataCategory() {
        return dataCategory;
    }

    public ConsentScopeType getScopeType() {
        return scopeType;
    }

    public UUID getScopeRefId() {
        return scopeRefId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public ConsentStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
