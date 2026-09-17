package com.healthcloud.deadletter;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for {@link DeadLetterEvent}. {@link #existsByDltTopicAndDltPartitionAndDltOffset} is the drainer's
 * idempotency pre-check (the unique constraint on those coordinates is the backstop); the org-scoped finder backs
 * the tenant admin's inspection list.
 */
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    /** Whether a DLT record has already been drained — idempotency for a redelivered DLT record. */
    boolean existsByDltTopicAndDltPartitionAndDltOffset(String dltTopic, int dltPartition, long dltOffset);

    /**
     * A page of the tenant's dead-letter events (§Phase 9), optionally filtered to a free-text search term. The
     * search runs in SQL ({@code null} {@code q} = no search — else a case-insensitive "contains" match on the
     * event id <i>or</i> the message key, both PHI-free identifiers; the UUID {@code eventId} is cast to text so a
     * partial id matches). Ordering/paging come from the {@link Pageable}.
     */
    @Query("select d from DeadLetterEvent d where d.organizationId = :org "
            + "and (:q is null "
            + "  or lower(cast(d.eventId as string)) like lower(cast(:q as string)) escape '\\' "
            + "  or lower(d.messageKey) like lower(cast(:q as string)) escape '\\')")
    Page<DeadLetterEvent> searchAll(UUID org, String q, Pageable pageable);

    /** Load one of the tenant's dead-letter events for replay — a cross-tenant/unknown id is simply absent (404). */
    Optional<DeadLetterEvent> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
