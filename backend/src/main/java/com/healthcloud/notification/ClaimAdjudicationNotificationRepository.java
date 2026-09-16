package com.healthcloud.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link ClaimAdjudicationNotification}. {@link #existsByEventId} is the consumer's idempotency
 * pre-check (the unique constraint is the backstop). The org-scoped finder is tenant-safe for a future read.
 */
public interface ClaimAdjudicationNotificationRepository
        extends JpaRepository<ClaimAdjudicationNotification, UUID> {

    /** Whether an event has already been consumed — the idempotency check for at-least-once delivery. */
    boolean existsByEventId(UUID eventId);

    /** The tenant's notifications, newest first (for a future read endpoint / UI). */
    List<ClaimAdjudicationNotification> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
