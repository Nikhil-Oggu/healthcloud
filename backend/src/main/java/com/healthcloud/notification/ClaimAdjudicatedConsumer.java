package com.healthcloud.notification;

import com.healthcloud.adjudication.ClaimAdjudicatedEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The read side of the transactional-outbox pipeline (source-of-truth §Phase 8): a Kafka consumer that reacts to
 * {@code claim.adjudicated} events by recording a PHI-free notification. It builds the notification purely from the
 * event (JSON payload + headers) and never re-reads the claim — a loosely-coupled downstream reaction.
 *
 * <p><b>Idempotent</b> because the relay is at-least-once: the same event may arrive more than once. Each event
 * carries the outbox {@code eventId} in a header; the consumer skips an event it has already recorded
 * ({@link ClaimAdjudicationNotificationRepository#existsByEventId}), and the unique {@code event_id} constraint is
 * the backstop for a redelivery race (a violation is caught and treated as already-processed). The consumer starts
 * only when {@code healthcloud.kafka.consumers.enabled} is true (the broker-free test suite leaves it off).
 *
 * <p><b>Honest limitation:</b> a parse/processing error currently uses Spring Kafka's default handling; a proper
 * retry/backoff + dead-letter topic is a later Phase 8 slice.
 */
@Component
public class ClaimAdjudicatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClaimAdjudicatedConsumer.class);

    static final String HEADER_EVENT_ID = "eventId";
    static final String HEADER_ORGANIZATION_ID = "organizationId";

    private final ClaimAdjudicationNotificationRepository notifications;
    private final ObjectMapper objectMapper;

    public ClaimAdjudicatedConsumer(ClaimAdjudicationNotificationRepository notifications,
                                    ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "claim.adjudicated",
            groupId = "claim-adjudication-notifier",
            autoStartup = "${healthcloud.kafka.consumers.enabled:true}")
    public void onClaimAdjudicated(ConsumerRecord<String, String> record) {
        UUID eventId = requireHeaderUuid(record, HEADER_EVENT_ID);
        UUID organizationId = requireHeaderUuid(record, HEADER_ORGANIZATION_ID);

        // Idempotency: skip an event we have already recorded (the relay delivers at-least-once).
        if (notifications.existsByEventId(eventId)) {
            log.debug("Skipping already-consumed claim.adjudicated event {}", eventId);
            return;
        }

        ClaimAdjudicatedEvent event = objectMapper.readValue(record.value(), ClaimAdjudicatedEvent.class);
        String message = "Claim " + event.claimNumber() + " adjudicated (v" + event.adjudicationVersion()
                + ", " + event.outcome() + "): plan paid $" + event.totalPlanPaidAmount()
                + ", member owes $" + event.totalMemberResponsibility();

        try {
            notifications.save(new ClaimAdjudicationNotification(organizationId, event.claimId(), eventId, message));
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent redelivery inserted the same event_id first — the unique constraint backstops the
            // pre-check. Treat as already-processed (idempotent), do not fail the consumer.
            log.debug("claim.adjudicated event {} already recorded (unique constraint) — treating as idempotent",
                    eventId);
        }
    }

    private static UUID requireHeaderUuid(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) {
            throw new IllegalArgumentException("claim.adjudicated event missing required header: " + key);
        }
        return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
    }
}
