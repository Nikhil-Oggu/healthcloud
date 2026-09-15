package com.healthcloud.coverage;

import com.healthcloud.coding.CodeSystem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plan fee-schedule entries, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId}. The adjudication engine reads a plan's fee schedule to price covered lines; the coverage
 * API manages the entries (ORG_ADMIN).
 */
public interface PlanFeeScheduleRepository extends JpaRepository<PlanFeeScheduleEntry, UUID> {

    /** One entry within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<PlanFeeScheduleEntry> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A plan's fee-schedule entries within the tenant, ordered by code for stable listing. */
    List<PlanFeeScheduleEntry> findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(
            UUID organizationId, UUID coveragePlanId);

    /** Whether a plan already prices this code (for a clean 409 on create). */
    boolean existsByOrganizationIdAndCoveragePlanIdAndCodeSystemAndCode(
            UUID organizationId, UUID coveragePlanId, CodeSystem codeSystem, String code);
}
