package com.healthcloud.claimreview;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The append-only claim-review status timeline, tenant-owned and always read for one review (§32.10). Ordered
 * oldest first so the timeline reads chronologically.
 */
public interface ClaimReviewStatusHistoryRepository extends JpaRepository<ClaimReviewStatusHistory, UUID> {

    /** A review's status history within the tenant, oldest first. */
    List<ClaimReviewStatusHistory> findByOrganizationIdAndClaimReviewIdOrderByCreatedAtAsc(
            UUID organizationId, UUID claimReviewId);
}
