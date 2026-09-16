package com.healthcloud.outbox;

import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.CorrelationId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The transactional outbox writer (source-of-truth §Phase 8). {@link #record} appends an integration event to the
 * outbox from <b>inside a domain service's own {@code @Transactional} method</b>, so the event joins that
 * transaction (§31.6) and commits atomically with the domain change — or both roll back. It is deliberately
 * <b>not</b> annotated {@code @Transactional}: like {@link com.healthcloud.audit.AuditService}, it must run within
 * the caller's transaction, not open its own. This realizes the "+ outbox event" half of the §31.6 one-transaction
 * pattern that the domain services have described aspirationally.
 *
 * <p>A separate relay will publish pending rows to Kafka after commit (a later Phase 8 slice); this service only
 * records them. The tenant + correlation id come from the backend context (never the client); the {@code payload}
 * object is serialized to JSON and must carry <b>minimum-necessary, PHI-free</b> data only (rule 5).
 */
@Service
public class OutboxService {

    public static final String AGGREGATE_CLAIM = "CLAIM";

    private final OutboxEventRepository events;
    private final UserContextAccessor userContext;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository events, UserContextAccessor userContext, ObjectMapper objectMapper) {
        this.events = events;
        this.userContext = userContext;
        this.objectMapper = objectMapper;
    }

    /**
     * Append an integration event to the current transaction's outbox. Call from within a domain service's
     * {@code @Transactional} method so the event commits with the domain change. The {@code payload} is serialized
     * to JSON; it must be a minimum-necessary, PHI-free view of the event (no clinical narrative, no patient
     * identifiers beyond the aggregate id).
     */
    public void record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        UUID organizationId = userContext.requireOrganizationId();
        String json = objectMapper.writeValueAsString(payload); // Jackson 3: unchecked JacksonException on failure
        events.save(new OutboxEvent(organizationId, aggregateType, aggregateId, eventType, json,
                CorrelationId.current()));
    }
}
