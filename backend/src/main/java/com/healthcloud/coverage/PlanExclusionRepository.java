package com.healthcloud.coverage;

import com.healthcloud.coding.CodeSystem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plan exclusions, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId}. The adjudication engine reads a plan's exclusions to gate covered lines; the coverage
 * API manages them (ORG_ADMIN).
 */
public interface PlanExclusionRepository extends JpaRepository<PlanExclusion, UUID> {

    /** One exclusion within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<PlanExclusion> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A plan's exclusions within the tenant, ordered by code for stable listing. */
    List<PlanExclusion> findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(
            UUID organizationId, UUID coveragePlanId);

    /** Whether a plan already excludes this code (for a clean 409 on create). */
    boolean existsByOrganizationIdAndCoveragePlanIdAndCodeSystemAndCode(
            UUID organizationId, UUID coveragePlanId, CodeSystem codeSystem, String code);
}
