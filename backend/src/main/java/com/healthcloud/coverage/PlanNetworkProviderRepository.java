package com.healthcloud.coverage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plan network providers, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId}. The coverage API manages a plan's network (ORG_ADMIN); the adjudication engine (slice
 * 19) reads it to decide whether a claim's rendering provider is in-network.
 */
public interface PlanNetworkProviderRepository extends JpaRepository<PlanNetworkProvider, UUID> {

    /** One network entry within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<PlanNetworkProvider> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A plan's network providers within the tenant, oldest first (the service re-sorts by name for display). */
    List<PlanNetworkProvider> findByOrganizationIdAndCoveragePlanIdOrderByCreatedAtAsc(
            UUID organizationId, UUID coveragePlanId);

    /** Whether a provider is already in a plan's network (for a clean 409 on create). */
    boolean existsByOrganizationIdAndCoveragePlanIdAndProviderUserId(
            UUID organizationId, UUID coveragePlanId, UUID providerUserId);

    /** Whether a plan defines a network at all (the engine's opt-in hook: no rows → no network restriction). */
    boolean existsByOrganizationIdAndCoveragePlanId(UUID organizationId, UUID coveragePlanId);
}
