package com.healthcloud.coverage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Coverage plans, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's plan is simply not
 * found (a secure 404). Not patient-scoped: a plan is administrative benefit config, not PHI.
 */
public interface CoveragePlanRepository extends JpaRepository<CoveragePlan, UUID> {

    /** One plan within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<CoveragePlan> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** All plans in the tenant, ordered by code for stable listing. */
    List<CoveragePlan> findByOrganizationIdOrderByPlanCodeAsc(UUID organizationId);

    /** Whether a plan code is already taken within the tenant (for a clean 409 on create). */
    boolean existsByOrganizationIdAndPlanCode(UUID organizationId, String planCode);
}
