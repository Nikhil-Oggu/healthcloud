package com.healthcloud.deadletter;

import com.healthcloud.audit.AuditAction;
import com.healthcloud.audit.AuditOutcome;
import com.healthcloud.audit.AuditService;
import com.healthcloud.common.PageResponse;
import com.healthcloud.common.SearchTerms;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.NotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to the dead-letter store (source-of-truth §Phase 8 slice 5). Inspecting failed messages is an
 * administrative/operational action, gated to ORG_ADMIN and tenant-scoped: an admin sees only their own tenant's
 * dead letters (a role-gated list → a disallowed role is a flat 403). Replay (re-driving a record back onto the
 * source topic) is a later slice.
 */
@Service
@Transactional(readOnly = true)
public class DeadLetterService {

    private static final String[] INSPECT_ROLES = {"ORG_ADMIN"};

    private final DeadLetterEventRepository deadLetters;
    private final UserContextAccessor userContext;
    private final AuditService auditService;

    public DeadLetterService(DeadLetterEventRepository deadLetters, UserContextAccessor userContext,
                             AuditService auditService) {
        this.deadLetters = deadLetters;
        this.userContext = userContext;
        this.auditService = auditService;
    }

    /** A page of the caller's tenant's dead-letter events (§Phase 9, ORG_ADMIN), optionally free-text searched. */
    public PageResponse<DeadLetterEventDto> listForTenant(String q, Pageable pageable) {
        userContext.requireAnyRole(INSPECT_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        String search = SearchTerms.likeContains(q); // free-text on event id / message key (§Phase 9 slice 8)
        return PageResponse.of(deadLetters.searchAll(organizationId, search, pageable), DeadLetterEventDto::from);
    }

    /**
     * Mark a dead-letter record as replayed and write a {@code DEAD_LETTER_REPLAYED} audit event, atomically
     * (§31.6). Called by {@link DeadLetterReplayService} <b>after</b> the message has been re-published, so the two
     * commit together — or both roll back. The record is loaded tenant-scoped again in this transaction; a missing
     * id is a secure 404. Actor + tenant come from the backend context (never the client). The audit detail is
     * PHI-free (rule 5): the record id, its source topic, and the outbox event id.
     */
    @Transactional
    public DeadLetterEventDto finalizeReplay(UUID id) {
        UUID organizationId = userContext.requireOrganizationId();
        UUID actorUserId = userContext.requireUser().userId();
        DeadLetterEvent event = deadLetters.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(NotFoundException::new);

        event.markReplayed(actorUserId);
        deadLetters.save(event);
        auditService.record(AuditAction.DEAD_LETTER_REPLAYED, AuditService.RESOURCE_DEAD_LETTER_EVENT, id,
                AuditOutcome.SUCCESS,
                "replayed dead-letter event " + id + " onto " + event.getSourceTopic()
                        + " (eventId=" + event.getEventId() + ")");
        return DeadLetterEventDto.from(event);
    }
}
