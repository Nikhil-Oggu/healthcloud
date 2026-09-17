package com.healthcloud.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Tenant-safe repository for {@link AuditEvent}. Every finder is constrained by {@code organizationId} (no bare
 * {@code findById} in business code), so another tenant's events are simply not returned. The trail is
 * append-only, so there is no update/delete finder. The auditor's views are paged (§Phase 9) so a default page
 * cannot pull an unbounded result.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * A page of the tenant's events (§Phase 9), optionally filtered to one action. The action is filtered in SQL
     * ({@code null} = any action); ordering/paging come from the {@link Pageable}. Replaces the old capped view.
     */
    @Query("select e from AuditEvent e where e.organizationId = :org "
            + "and (:action is null or e.action = :action)")
    Page<AuditEvent> searchRecent(UUID org, AuditAction action, Pageable pageable);

    /** A page of a single resource's audit history in the tenant (the {@code ?resourceType=&resourceId=} view). */
    @Query("select e from AuditEvent e where e.organizationId = :org "
            + "and e.resourceType = :resourceType and e.resourceId = :resourceId")
    Page<AuditEvent> searchForResource(UUID org, String resourceType, UUID resourceId, Pageable pageable);

    /** The org's whole chain in append order — the sequence verification walks to check integrity. */
    List<AuditEvent> findByOrganizationIdOrderBySequenceNoAsc(UUID organizationId);
}
