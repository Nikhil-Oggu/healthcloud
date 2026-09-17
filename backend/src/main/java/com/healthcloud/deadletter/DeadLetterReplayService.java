package com.healthcloud.deadletter;

import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dead-letter replay (source-of-truth §Phase 8 slice 6): an ORG_ADMIN re-drives a stored {@link DeadLetterEvent}
 * back onto its <b>source topic</b> once the underlying cause is fixed, so the now-working consumer gets another
 * chance. The record is stamped replayed and the action is audited.
 *
 * <p><b>Transaction shape.</b> A Kafka publish cannot join a DB transaction, so — like the reprocessing orchestrator
 * — this runs {@code NOT_SUPPORTED} (no ambient tx around the send) and delegates the atomic "mark replayed + audit"
 * write to {@link DeadLetterService#finalizeReplay} (a separate bean, so its {@code @Transactional} is honoured).
 * The order is <b>publish first, then mark</b>: if the process dies after the send but before the mark, a retry
 * simply re-publishes — and because the consumer is idempotent (dedupes on {@code event_id}), the redelivery is
 * skipped. That same idempotency makes a rare concurrent double-click harmless (a duplicate publish, one effect).
 */
@Service
public class DeadLetterReplayService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);

    private static final String[] REPLAY_ROLES = {"ORG_ADMIN"};
    private static final String HEADER_EVENT_ID = "eventId";
    private static final String HEADER_ORGANIZATION_ID = "organizationId";

    private final DeadLetterEventRepository deadLetters;
    private final DeadLetterService deadLetterService;
    private final UserContextAccessor userContext;
    // Boot's KafkaTemplate<?,?> does not match a KafkaTemplate<String,String> injection point — inject it raw, as
    // OutboxRelay does. The producer uses String serializers (application.yml).
    @SuppressWarnings("rawtypes")
    private final KafkaTemplate kafkaTemplate;
    private final long sendTimeoutMs;

    public DeadLetterReplayService(DeadLetterEventRepository deadLetters, DeadLetterService deadLetterService,
                                   UserContextAccessor userContext,
                                   @SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate,
                                   @Value("${healthcloud.deadletter.replay.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.deadLetters = deadLetters;
        this.deadLetterService = deadLetterService;
        this.userContext = userContext;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /**
     * Re-drive a dead-letter record onto its source topic (ORG_ADMIN), then mark it replayed and audit the action.
     * A cross-tenant/unknown id is a secure 404; an already-replayed record is a 409.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DeadLetterEventDto replay(UUID id) {
        userContext.requireAnyRole(REPLAY_ROLES);
        UUID organizationId = userContext.requireOrganizationId();

        DeadLetterEvent event = deadLetters.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(NotFoundException::new);
        if (event.isReplayed()) {
            throw new ConflictException("Dead-letter event has already been replayed");
        }

        publishToSourceTopic(event);
        log.info("Replayed dead-letter event {} onto source topic {}", event.getId(), event.getSourceTopic());
        return deadLetterService.finalizeReplay(id);
    }

    @SuppressWarnings("unchecked") // raw KafkaTemplate.send(ProducerRecord) — see the field's note
    private void publishToSourceTopic(DeadLetterEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getSourceTopic(), event.getMessageKey(), event.getPayload());
        addHeader(record, HEADER_EVENT_ID, event.getEventId());
        addHeader(record, HEADER_ORGANIZATION_ID, event.getOrganizationId());
        try {
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while replaying dead-letter event " + event.getId(), e);
        } catch (Exception e) {
            // The send failed — leave the record un-stamped so the admin can retry (no false "replayed" state).
            throw new IllegalStateException("Failed to replay dead-letter event " + event.getId(), e);
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String key, UUID value) {
        if (value != null) {
            record.headers().add(key, value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
