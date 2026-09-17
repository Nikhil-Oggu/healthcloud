package com.healthcloud.audit;

import com.healthcloud.common.PageRequests;
import com.healthcloud.common.PageResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The security audit trail API (source-of-truth §Phase 7). Thin controller (§31.5): the role gate, tenant scoping
 * and query live in {@link AuditService}. Read-only — the trail is append-only, written as a side effect of the
 * domain actions themselves, never through this API. Gated to AUDITOR/ORG_ADMIN; a disallowed role is a flat 403
 * (a role-gated list, not an existence-sensitive lookup).
 */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {

    /** Fields a caller may sort the audit trail by (allowlisted — an unknown field is a clean 400). */
    private static final Set<String> SORTABLE_FIELDS = Set.of("occurredAt", "sequenceNo");

    /** Default ordering when the caller supplies no {@code sort}: newest first. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "occurredAt");

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    /**
     * A page of the tenant's audit events (§Phase 9). Supply both {@code resourceType} and {@code resourceId} to
     * read a single resource's history; otherwise the tenant's events, optionally filtered by {@code action}.
     * Paging/sorting come from {@code page}/{@code size}/{@code sort}; an unknown sort field is a 400.
     */
    @GetMapping
    public PageResponse<AuditEventDto> list(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.toPageable(page, size, sort, SORTABLE_FIELDS, DEFAULT_SORT);
        return service.list(resourceType, resourceId, action, pageable);
    }

    /**
     * Verify the tenant's audit chain is intact (AUDITOR/ORG_ADMIN) — recomputes the per-org HMAC hash chain and
     * reports whether any row was modified, deleted, reordered, inserted or truncated.
     */
    @GetMapping("/verify")
    public AuditChainVerificationDto verify() {
        return service.verify();
    }
}
