package com.healthcloud.deadletter;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link DeadLetterEvent}. {@link #existsByDltTopicAndDltPartitionAndDltOffset} is the drainer's
 * idempotency pre-check (the unique constraint on those coordinates is the backstop); the org-scoped finder backs
 * the tenant admin's inspection list.
 */
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    /** Whether a DLT record has already been drained — idempotency for a redelivered DLT record. */
    boolean existsByDltTopicAndDltPartitionAndDltOffset(String dltTopic, int dltPartition, long dltOffset);

    /** A page of the tenant's dead-letter events (§Phase 9); ordering/paging come from the {@link Pageable}. */
    Page<DeadLetterEvent> findByOrganizationId(UUID organizationId, Pageable pageable);

    /** Load one of the tenant's dead-letter events for replay — a cross-tenant/unknown id is simply absent (404). */
    Optional<DeadLetterEvent> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
