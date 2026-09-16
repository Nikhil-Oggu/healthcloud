package com.healthcloud.coverage;

import com.healthcloud.coding.CodeSystem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plan prior-authorization requirements, tenant-owned. Like every tenant-owned repository (§32.10) the finders
 * are scoped by {@code organizationId}. The adjudication engine reads a plan's requirements to gate covered
 * lines; the coverage API manages them (ORG_ADMIN).
 */
public interface PlanPriorAuthRequirementRepository extends JpaRepository<PlanPriorAuthRequirement, UUID> {

    /** One requirement within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<PlanPriorAuthRequirement> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A plan's prior-auth requirements within the tenant, ordered by code for stable listing. */
    List<PlanPriorAuthRequirement> findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(
            UUID organizationId, UUID coveragePlanId);

    /** Whether a plan already requires prior auth for this code (for a clean 409 on create). */
    boolean existsByOrganizationIdAndCoveragePlanIdAndCodeSystemAndCode(
            UUID organizationId, UUID coveragePlanId, CodeSystem codeSystem, String code);
}
