package com.healthcloud.audit;

import java.util.List;
import java.util.UUID;
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

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    /**
     * The tenant's audit events, newest first. Supply both {@code resourceType} and {@code resourceId} to read a
     * single resource's history; otherwise the tenant's most recent events (capped).
     */
    @GetMapping
    public List<AuditEventDto> list(@RequestParam(required = false) String resourceType,
                                    @RequestParam(required = false) UUID resourceId) {
        return service.list(resourceType, resourceId);
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
