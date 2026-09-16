package com.healthcloud.reprocessing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reprocessing items, tenant-owned. Finders are scoped by {@code organizationId} (§32.10) so another tenant's
 * rows are simply not found. Items are read only as the children of a batch the caller already reached.
 */
public interface ReprocessingItemRepository extends JpaRepository<ReprocessingItem, UUID> {

    /** The items of one batch within the tenant, oldest first (stable reporting order). */
    List<ReprocessingItem> findByOrganizationIdAndReprocessingBatchIdOrderByCreatedAtAsc(
            UUID organizationId, UUID reprocessingBatchId);
}
