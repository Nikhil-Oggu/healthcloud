package com.healthcloud.outbox;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-safe repository for {@link OutboxEvent}. Business finders are constrained by {@code organizationId}. The
 * relay's poll ({@link #findByPublishedAtIsNullOrderByOccurredAtAsc}) is deliberately cross-tenant — publishing is
 * a platform background job, not a tenant-scoped user request — and is backed by the partial index on the pending
 * backlog. The relay itself arrives in a later Phase 8 slice; this slice only writes the rows.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** The pending backlog, oldest first — the relay's poll. Cross-tenant by design (publishing is a platform job). */
    List<OutboxEvent> findByPublishedAtIsNullOrderByOccurredAtAsc();

    /** One capped batch of the pending backlog, oldest first — the relay polls a bounded page per tick. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByOccurredAtAsc(Pageable pageable);

    /** How many events are still pending (unpublished). Cheap count backed by the partial index — the outbox health
     *  indicator's backlog gauge (Phase 11 slice 4). Cross-tenant by design (publishing is a platform job). */
    long countByPublishedAtIsNull();

    /** The oldest pending event, if any — the health indicator derives the backlog age from its {@code occurredAt}. */
    Optional<OutboxEvent> findFirstByPublishedAtIsNullOrderByOccurredAtAsc();

    /** This tenant's events for one aggregate, newest first (used by tests and future reads). */
    List<OutboxEvent> findByOrganizationIdAndAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
            UUID organizationId, String aggregateType, UUID aggregateId);
}
