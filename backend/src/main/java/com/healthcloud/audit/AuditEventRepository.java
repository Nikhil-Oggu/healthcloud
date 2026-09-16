package com.healthcloud.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-safe repository for {@link AuditEvent}. Every finder is constrained by {@code organizationId} (no bare
 * {@code findById} in business code), so another tenant's events are simply not returned. The trail is
 * append-only, so there is no update/delete finder. The unfiltered view is capped ({@code findTop200…}) so an
 * auditor's default page cannot pull an unbounded result.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /** A tenant's most recent events, newest first (capped). */
    List<AuditEvent> findTop200ByOrganizationIdOrderByOccurredAtDesc(UUID organizationId);

    /** Every event on a single resource in the tenant, newest first (a resource's audit history). */
    List<AuditEvent> findByOrganizationIdAndResourceTypeAndResourceIdOrderByOccurredAtDesc(
            UUID organizationId, String resourceType, UUID resourceId);

    /** The org's whole chain in append order — the sequence verification walks to check integrity. */
    List<AuditEvent> findByOrganizationIdOrderBySequenceNoAsc(UUID organizationId);
}
