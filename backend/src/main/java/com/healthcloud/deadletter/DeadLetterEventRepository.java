package com.healthcloud.deadletter;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link DeadLetterEvent}. {@link #existsByDltTopicAndDltPartitionAndDltOffset} is the drainer's
 * idempotency pre-check (the unique constraint on those coordinates is the backstop); the org-scoped finder backs
 * the tenant admin's inspection list.
 */
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    /** Whether a DLT record has already been drained — idempotency for a redelivered DLT record. */
    boolean existsByDltTopicAndDltPartitionAndDltOffset(String dltTopic, int dltPartition, long dltOffset);

    /** The tenant's dead-letter events, newest first (the admin inspection list). */
    List<DeadLetterEvent> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
