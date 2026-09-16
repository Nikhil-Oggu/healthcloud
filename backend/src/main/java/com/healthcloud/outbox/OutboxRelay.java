package com.healthcloud.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional-outbox relay (source-of-truth §Phase 8): the second half of the outbox pattern. Domain
 * services write {@link OutboxEvent} rows inside their own transaction (slice 1); this relay publishes the
 * committed-but-unpublished rows to Kafka <b>after</b> that commit and stamps {@code published_at}, so an event is
 * never published unless its domain change is durable and every committed event is eventually published.
 *
 * <p>Each pending row (oldest first, one bounded page per poll) is sent synchronously to a topic named after its
 * {@code event_type}, keyed by {@code aggregate_id} (per-aggregate ordering), with the payload as the value and
 * the event metadata as headers. On a send failure the batch stops (preserving order and not spinning against a
 * dead broker) — the row stays pending and is retried on the next poll.
 *
 * <p><b>Delivery is at-least-once.</b> If the process dies after a successful Kafka send but before the
 * {@code published_at} commit, the row is re-published next poll — so consumers must be idempotent (a later
 * Phase 8 slice). This relay also assumes a <b>single instance</b>; running several would need
 * {@code SELECT … FOR UPDATE SKIP LOCKED} on the batch (a near-term refinement). Retry/backoff and a DLQ are
 * later slices too.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String HEADER_EVENT_ID = "eventId";
    private static final String HEADER_EVENT_TYPE = "eventType";
    private static final String HEADER_AGGREGATE_TYPE = "aggregateType";
    private static final String HEADER_ORGANIZATION_ID = "organizationId";
    private static final String HEADER_CORRELATION_ID = "correlationId";

    private final OutboxEventRepository events;
    // Boot auto-configures a KafkaTemplate<?,?>, which does not match a KafkaTemplate<String,String> injection
    // point (wildcard vs specific generics), so inject it raw. The producer uses String serializers (application.yml).
    @SuppressWarnings("rawtypes")
    private final KafkaTemplate kafkaTemplate;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxRelay(OutboxEventRepository events, @SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate,
                       @Value("${healthcloud.outbox.relay.batch-size}") int batchSize,
                       @Value("${healthcloud.outbox.relay.send-timeout-ms}") long sendTimeoutMs) {
        this.events = events;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /**
     * Publish one bounded batch of pending outbox rows to Kafka, oldest first, stamping each as published on
     * success. Runs in a transaction so the {@code published_at} stamps for the rows sent this call commit
     * together; a send failure stops the batch (the unsent rows stay pending). Returns the number published.
     */
    @Transactional
    @SuppressWarnings("unchecked") // raw KafkaTemplate.send(ProducerRecord) — see the field's note
    public int publishPending() {
        List<OutboxEvent> batch = events.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, batchSize));
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(toRecord(event)).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted after publishing {} event(s); {} remain pending",
                        published, batch.size() - published);
                break;
            } catch (Exception e) {
                // Leave this row (and the rest of the batch) pending; it will be retried on the next poll.
                log.warn("Outbox relay failed to publish event {} (type {}); stopping this batch, will retry",
                        event.getId(), event.getEventType(), e);
                break;
            }
            event.markPublished();
            published++;
        }
        if (published > 0) {
            log.debug("Outbox relay published {} event(s)", published);
        }
        return published;
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getEventType(), event.getAggregateId().toString(), event.getPayload());
        addHeader(record, HEADER_EVENT_ID, event.getId().toString());
        addHeader(record, HEADER_EVENT_TYPE, event.getEventType());
        addHeader(record, HEADER_AGGREGATE_TYPE, event.getAggregateType());
        addHeader(record, HEADER_ORGANIZATION_ID, event.getOrganizationId().toString());
        addHeader(record, HEADER_CORRELATION_ID, event.getCorrelationId());
        return record;
    }

    private static void addHeader(ProducerRecord<String, String> record, String key, String value) {
        if (value != null) {
            record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
