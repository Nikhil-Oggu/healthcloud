package com.healthcloud.audit;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.CorrelationId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The security audit trail (source-of-truth §Phase 7). Two responsibilities:
 *
 * <ul>
 *   <li><b>{@link #record}</b> — write an audit event. It is called from <b>inside a domain service's own
 *       {@code @Transactional} method</b>, so the event joins that transaction (§31.6) and commits atomically with
 *       the change it records — or both roll back. It derives the tenant + actor from the backend {@link UserContext}
 *       (never the client) and the correlation id from {@link CorrelationId}, so a caller records an action in one
 *       line. It is deliberately <i>not</i> annotated {@code @Transactional}: it must run within the caller's
 *       transaction, not open its own.</li>
 *   <li><b>{@link #list}</b> — the auditor's read, gated to {@link #READ_ROLES} and tenant-scoped.</li>
 * </ul>
 *
 * <p>Resource types are passed as short coded strings (see the {@code RESOURCE_*} constants) rather than an enum,
 * so a new audited resource does not require a schema/enum change — the {@code action} enum already names the event.
 */
@Service
public class AuditService {

    /** Roles allowed to read the audit trail. The long-seeded AUDITOR role gets its first job here. */
    static final String[] READ_ROLES = {"AUDITOR", "ORG_ADMIN"};

    public static final String RESOURCE_CLAIM = "CLAIM";
    public static final String RESOURCE_CONSENT_DIRECTIVE = "CONSENT_DIRECTIVE";

    private final AuditEventRepository events;
    private final UserContextAccessor userContext;

    public AuditService(AuditEventRepository events, UserContextAccessor userContext) {
        this.events = events;
        this.userContext = userContext;
    }

    /**
     * Record a security-relevant event in the <b>current</b> transaction. Call this from within a domain service's
     * {@code @Transactional} method so the event commits with the domain change. The tenant and actor come from the
     * backend context; {@code detail} must be a short, PHI-free description (rule 5).
     */
    public void record(AuditAction action, String resourceType, UUID resourceId,
                       AuditOutcome outcome, String detail) {
        UUID organizationId = userContext.requireOrganizationId();
        UUID actorUserId = userContext.current().map(UserContext::userId).orElse(null);
        events.save(new AuditEvent(organizationId, actorUserId, action, resourceType, resourceId,
                outcome, CorrelationId.current(), detail));
    }

    /**
     * The tenant's audit events, newest first (AUDITOR/ORG_ADMIN). With both {@code resourceType} and
     * {@code resourceId} supplied, returns just that resource's history; otherwise the tenant's most recent events
     * (capped). A role-gated list, so a disallowed role is a flat 403 (not a secure 404).
     */
    @Transactional(readOnly = true)
    public List<AuditEventDto> list(String resourceType, UUID resourceId) {
        userContext.requireAnyRole(READ_ROLES);
        UUID organizationId = userContext.requireOrganizationId();

        List<AuditEvent> rows = (resourceType != null && resourceId != null)
                ? events.findByOrganizationIdAndResourceTypeAndResourceIdOrderByOccurredAtDesc(
                        organizationId, resourceType, resourceId)
                : events.findTop200ByOrganizationIdOrderByOccurredAtDesc(organizationId);
        return rows.stream().map(AuditEventDto::from).toList();
    }
}
