package com.healthcloud.appeal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The append-only appeal status timeline, tenant-owned and always read for one appeal (§32.10). Ordered oldest
 * first so the timeline reads chronologically.
 */
public interface AppealStatusHistoryRepository extends JpaRepository<AppealStatusHistory, UUID> {

    /** An appeal's status history within the tenant, oldest first. */
    List<AppealStatusHistory> findByOrganizationIdAndAppealIdOrderByCreatedAtAsc(
            UUID organizationId, UUID appealId);
}
