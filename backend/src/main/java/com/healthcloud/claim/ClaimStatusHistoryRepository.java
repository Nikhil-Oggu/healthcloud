package com.healthcloud.claim;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The append-only claim status timeline, tenant-owned and always read for one claim (§32.10). Ordered oldest
 * first so the timeline reads chronologically.
 */
public interface ClaimStatusHistoryRepository extends JpaRepository<ClaimStatusHistory, UUID> {

    /** A claim's status history within the tenant, oldest first. */
    List<ClaimStatusHistory> findByOrganizationIdAndClaimIdOrderByCreatedAtAsc(UUID organizationId, UUID claimId);
}
