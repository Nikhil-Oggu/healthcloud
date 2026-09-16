package com.healthcloud.referral;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The append-only referral status timeline, tenant-owned and always read for one referral (§32.10). Ordered
 * oldest first so the timeline reads chronologically.
 */
public interface ReferralStatusHistoryRepository extends JpaRepository<ReferralStatusHistory, UUID> {

    /** A referral's status history within the tenant, oldest first. */
    List<ReferralStatusHistory> findByOrganizationIdAndReferralIdOrderByCreatedAtAsc(
            UUID organizationId, UUID referralId);
}
