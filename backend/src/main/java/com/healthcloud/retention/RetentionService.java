package com.healthcloud.retention;

import com.healthcloud.audit.AuditAction;
import com.healthcloud.audit.AuditOutcome;
import com.healthcloud.audit.AuditService;
import com.healthcloud.breakglass.BreakGlassGrantRepository;
import com.healthcloud.context.UserContextAccessor;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data retention (source-of-truth §Phase 7) — the last governance area. Retention is the policy for how long
 * <b>operational</b> data lives before it is purged, held in deliberate tension with the audit trail:
 *
 * <ul>
 *   <li>The <b>audit trail</b> ({@code audit_event}) is the tamper-evident, immutable record. It is <b>never</b>
 *       purged — deleting a row would break the per-org hash chain by design (that is the whole point of slice 2).
 *       Audit events are permanent.</li>
 *   <li>A <b>break-glass grant</b> is operational data carrying the most sensitive field in this area — the
 *       free-text emergency {@code reason}. Once a grant is long expired and has been reviewed, that reason no
 *       longer needs to sit in the live database, so it is purged past a retention window.</li>
 * </ul>
 *
 * <p>The result demonstrates the real principle: purge operational data on a policy, but keep the immutable audit
 * record. The PHI-free {@code BREAK_GLASS_INVOKED} / {@code BREAK_GLASS_REVOKED} events survive the purge (proving
 * the emergency access <i>happened</i>), and the purge itself is audited as a {@code RETENTION_PURGED} event in the
 * <b>same transaction</b> (§31.6) — the delete and its audit record commit together or both roll back. Tenant-scoped
 * (only the caller's org's grants) and ORG_ADMIN-gated.
 *
 * <p><b>Honest MVP limitation:</b> this is a manual admin-triggered run. A scheduled, automatic purge belongs with
 * the worker/event infrastructure in Phase 8; audit-event retention is intentionally out of scope (permanent).
 */
@Service
public class RetentionService {

    /** Only an administrator runs retention — a governance/operational action (an auditor is read-only). */
    private static final String[] RETENTION_ROLES = {"ORG_ADMIN"};

    private final BreakGlassGrantRepository grants;
    private final AuditService audit;
    private final UserContextAccessor userContext;
    private final long breakGlassRetentionDays;

    public RetentionService(BreakGlassGrantRepository grants, AuditService audit, UserContextAccessor userContext,
                            @Value("${healthcloud.retention.break-glass-days}") long breakGlassRetentionDays) {
        this.grants = grants;
        this.audit = audit;
        this.userContext = userContext;
        this.breakGlassRetentionDays = breakGlassRetentionDays;
    }

    /**
     * Purge the caller's-tenant break-glass grants that expired more than the retention window ago, and record a
     * {@code RETENTION_PURGED} audit event — in one transaction. Only long-<b>expired</b> grants are removed (the
     * cutoff is strictly in the past, so a live grant is never touched); the audit events proving break-glass
     * happened are permanent and remain. ORG_ADMIN-gated; tenant-scoped by {@code organizationId}.
     */
    @Transactional
    public RetentionPurgeResultDto runBreakGlassPurge() {
        userContext.requireAnyRole(RETENTION_ROLES);
        UUID organizationId = userContext.requireOrganizationId();

        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(breakGlassRetentionDays);
        long purged = grants.deleteByOrganizationIdAndExpiresAtBefore(organizationId, cutoff);

        // PHI-free detail (rule 5): the count + the policy window only — never a patient id or the emergency reason.
        audit.record(AuditAction.RETENTION_PURGED, AuditService.RESOURCE_BREAK_GLASS_GRANT, null,
                AuditOutcome.SUCCESS,
                "Retention purge: removed " + purged + " expired break-glass grant(s) older than "
                        + breakGlassRetentionDays + " days");

        return new RetentionPurgeResultDto(purged, breakGlassRetentionDays, cutoff);
    }
}
