package com.healthcloud.reprocessing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reprocessing batches, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's batch is simply not
 * found (a secure 404). A batch's scope is a plan (not PHI) and it is a reviewer/admin work record, so there is
 * no per-patient relationship gate here (the underlying per-claim re-adjudication is itself fully gated).
 */
public interface ReprocessingBatchRepository extends JpaRepository<ReprocessingBatch, UUID> {

    /** One batch within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<ReprocessingBatch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Whether a batch number is already taken within the tenant (for a clean number allocation). */
    boolean existsByOrganizationIdAndBatchNumber(UUID organizationId, String batchNumber);

    /** All batches in the tenant, newest first (the work queue). */
    List<ReprocessingBatch> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
