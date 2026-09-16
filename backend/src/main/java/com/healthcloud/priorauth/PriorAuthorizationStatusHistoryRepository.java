package com.healthcloud.priorauth;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The append-only prior-auth status timeline, tenant-owned and always read for one authorization (§32.10).
 * Ordered oldest first so the timeline reads chronologically.
 */
public interface PriorAuthorizationStatusHistoryRepository
        extends JpaRepository<PriorAuthorizationStatusHistory, UUID> {

    /** A prior authorization's status history within the tenant, oldest first. */
    List<PriorAuthorizationStatusHistory> findByOrganizationIdAndPriorAuthorizationIdOrderByCreatedAtAsc(
            UUID organizationId, UUID priorAuthorizationId);
}
