package com.healthcloud.reprocessing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * A page of the tenant's reprocessing batches (§Phase 9), optionally filtered to one status and/or a free-text
     * search term. Both filters run in SQL ({@code null} status = any status; {@code null} {@code q} = no search —
     * else a case-insensitive "contains" match on the batch number, a PHI-free identifier); ordering/paging come
     * from the {@link Pageable}.
     */
    @Query("select b from ReprocessingBatch b where b.organizationId = :org "
            + "and (:status is null or b.status = :status) "
            + "and (:q is null or lower(b.batchNumber) like lower(cast(:q as string)) escape '\\')")
    Page<ReprocessingBatch> searchAll(UUID org, ReprocessingBatchStatus status, String q, Pageable pageable);
}
