package com.healthcloud.deadletter;

import com.healthcloud.context.UserContextAccessor;
import java.util.List;
import java.util.UUID;
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

    public DeadLetterService(DeadLetterEventRepository deadLetters, UserContextAccessor userContext) {
        this.deadLetters = deadLetters;
        this.userContext = userContext;
    }

    /** The caller's tenant's dead-letter events, newest first (ORG_ADMIN). */
    public List<DeadLetterEventDto> listForTenant() {
        userContext.requireAnyRole(INSPECT_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        return deadLetters.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .map(DeadLetterEventDto::from)
                .toList();
    }
}
