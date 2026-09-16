package com.healthcloud.audit;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.CorrelationId;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The security audit trail (source-of-truth §Phase 7). Three responsibilities:
 *
 * <ul>
 *   <li><b>{@link #record}</b> — append an audit event. Called from <b>inside a domain service's own
 *       {@code @Transactional} method</b>, so the event joins that transaction (§31.6) and commits atomically with
 *       the change it records — or both roll back. It derives the tenant + actor from the backend {@link UserContext}
 *       (never the client) and the correlation id from {@link CorrelationId}. It is deliberately <i>not</i>
 *       annotated {@code @Transactional}: it must run within the caller's transaction, not open its own.</li>
 *   <li><b>{@link #verify}</b> — recompute an org's hash chain and report whether it is intact.</li>
 *   <li><b>{@link #list}</b> — the auditor's read, gated to {@link #READ_ROLES} and tenant-scoped.</li>
 * </ul>
 *
 * <p><b>Tamper-evidence (slice 2).</b> Each append is chained: the org's {@link AuditChainHead} is taken under a
 * {@code PESSIMISTIC_WRITE} lock (insert-if-absent first, the {@code benefit_accumulator} pattern), so writes for one
 * org serialize; the new event's {@code entryHash} is {@code HMAC(orgKey, canonical(event, prevHash))} and the head
 * advances to it. Resource types are passed as short coded strings (the {@code RESOURCE_*} constants) rather than an
 * enum, so a new audited resource does not require a schema/enum change — the {@code action} enum names the event.
 */
@Service
public class AuditService {

    /** Roles allowed to read/verify the audit trail. The long-seeded AUDITOR role gets its first job here. */
    static final String[] READ_ROLES = {"AUDITOR", "ORG_ADMIN"};

    public static final String RESOURCE_CLAIM = "CLAIM";
    public static final String RESOURCE_CONSENT_DIRECTIVE = "CONSENT_DIRECTIVE";
    public static final String RESOURCE_PATIENT = "PATIENT";
    public static final String RESOURCE_BREAK_GLASS_GRANT = "BREAK_GLASS_GRANT";

    private final AuditEventRepository events;
    private final AuditChainHeadRepository chainHeads;
    private final AuditSigningKeys signingKeys;
    private final UserContextAccessor userContext;

    public AuditService(AuditEventRepository events, AuditChainHeadRepository chainHeads,
                        AuditSigningKeys signingKeys, UserContextAccessor userContext) {
        this.events = events;
        this.chainHeads = chainHeads;
        this.signingKeys = signingKeys;
        this.userContext = userContext;
    }

    /**
     * Append a security-relevant event to the current transaction's tenant chain. Call this from within a domain
     * service's {@code @Transactional} method so the event commits with the domain change. The tenant and actor come
     * from the backend context; {@code detail} must be a short, PHI-free description (rule 5).
     */
    public void record(AuditAction action, String resourceType, UUID resourceId,
                       AuditOutcome outcome, String detail) {
        UUID organizationId = userContext.requireOrganizationId();
        UUID actorUserId = userContext.current().map(UserContext::userId).orElse(null);
        // Truncate to microseconds (Postgres timestamptz precision) so the value we fingerprint is exactly what a
        // later verification reads back from the database.
        OffsetDateTime occurredAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String correlationId = CorrelationId.current();

        // Lock the org's chain tip for the rest of the transaction (serializes concurrent audit writes for the org).
        chainHeads.insertIfAbsent(organizationId, AuditHashChain.GENESIS);
        AuditChainHead head = chainHeads.lockByOrganizationId(organizationId)
                .orElseThrow(() -> new IllegalStateException("audit chain head missing after insert-if-absent"));

        long sequenceNo = head.getNextSequence();
        String prevHash = head.getLastHash();
        String canonical = AuditHashChain.canonical(organizationId, sequenceNo, occurredAt, actorUserId, action,
                resourceType, resourceId, outcome, correlationId, detail, prevHash);
        String entryHash = AuditHashChain.computeEntryHash(signingKeys.orgKey(organizationId), canonical);

        events.save(new AuditEvent(organizationId, occurredAt, actorUserId, action, resourceType, resourceId,
                outcome, correlationId, detail, sequenceNo, prevHash, entryHash));
        head.advance(entryHash);
        chainHeads.save(head);
    }

    /**
     * Verify the caller's tenant audit chain (AUDITOR/ORG_ADMIN). Walks the chain in sequence order, checking each
     * event's position, its previous-hash link, and its recomputed fingerprint, then cross-checks the chain tip
     * (catching truncation of the most recent rows). The first failure is reported; a fully intact chain is valid.
     */
    @Transactional(readOnly = true)
    public AuditChainVerificationDto verify() {
        userContext.requireAnyRole(READ_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        byte[] orgKey = signingKeys.orgKey(organizationId);

        List<AuditEvent> chain = events.findByOrganizationIdOrderBySequenceNoAsc(organizationId);
        String expectedPrev = AuditHashChain.GENESIS;
        long expectedSeq = 0;
        int checked = 0;
        for (AuditEvent event : chain) {
            if (event.getSequenceNo() != expectedSeq) {
                return AuditChainVerificationDto.broken(checked, event.getSequenceNo(),
                        "sequence break (a row was deleted, reordered or inserted)");
            }
            if (!event.getPrevHash().equals(expectedPrev)) {
                return AuditChainVerificationDto.broken(checked, event.getSequenceNo(),
                        "previous-hash link mismatch (a row was deleted, reordered or inserted)");
            }
            String canonical = AuditHashChain.canonical(organizationId, event.getSequenceNo(),
                    event.getOccurredAt(), event.getActorUserId(), event.getAction(), event.getResourceType(),
                    event.getResourceId(), event.getOutcome(), event.getCorrelationId(), event.getDetail(),
                    event.getPrevHash());
            if (!AuditHashChain.computeEntryHash(orgKey, canonical).equals(event.getEntryHash())) {
                return AuditChainVerificationDto.broken(checked, event.getSequenceNo(),
                        "entry-hash mismatch (a field was modified)");
            }
            expectedPrev = event.getEntryHash();
            expectedSeq++;
            checked++;
        }

        // Cross-check the tip: catches truncation (most-recent rows deleted without updating the head).
        AuditChainHead head = chainHeads.findById(organizationId).orElse(null);
        if (head != null && (head.getNextSequence() != expectedSeq || !head.getLastHash().equals(expectedPrev))) {
            return AuditChainVerificationDto.broken(checked, expectedSeq,
                    "chain tip mismatch (the most recent rows may have been truncated)");
        }
        return AuditChainVerificationDto.valid(checked);
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
