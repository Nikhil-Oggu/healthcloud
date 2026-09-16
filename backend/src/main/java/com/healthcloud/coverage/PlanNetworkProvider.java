package com.healthcloud.coverage;

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
 * A PROVIDER that participates in a {@link CoveragePlan}'s network (source-of-truth §Phase 6, provider network).
 * Plan config, tenant-owned via {@code organizationId} and NOT patient-scoped (like the plan itself and
 * {@code PlanExclusion} / {@code PlanPriorAuthRequirement}). Unlike those, it references a provider — an
 * {@code app_user} — rather than a medical code; because {@code app_user} is not tenant-keyed, the service
 * validates the provider is an active same-tenant PROVIDER (there is no FK-with-org on the provider). Immutable
 * once created (no {@code @Version}) — a provider is added to or removed from a network, not edited.
 *
 * <p>When the adjudication engine (slice 19) sees a covered claim line whose covering plan <b>defines a network</b>
 * and whose rendering provider is not in it, that line comes out OUT_OF_NETWORK; a plan with no network entries
 * imposes no network restriction.
 */
@Entity
@Table(name = "plan_network_provider")
public class PlanNetworkProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "coverage_plan_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID coveragePlanId;

    @Column(name = "provider_user_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID providerUserId;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PlanNetworkProvider() {
        // for JPA
    }

    public PlanNetworkProvider(UUID organizationId, UUID coveragePlanId, UUID providerUserId, UUID createdBy) {
        this.organizationId = organizationId;
        this.coveragePlanId = coveragePlanId;
        this.providerUserId = providerUserId;
        this.createdBy = createdBy;
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

    public UUID getCoveragePlanId() {
        return coveragePlanId;
    }

    public UUID getProviderUserId() {
        return providerUserId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
