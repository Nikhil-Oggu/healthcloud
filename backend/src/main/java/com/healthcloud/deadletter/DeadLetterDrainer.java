package com.healthcloud.deadletter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Drains the consumer dead-letter topic into {@link DeadLetterEvent} rows (source-of-truth §Phase 8 slice 5), so
 * failed records can be inspected — and later replayed — through ordinary tenant/role-gated APIs rather than by
 * consuming Kafka directly.
 *
 * <p>It reads the original app headers ({@code eventId}, {@code organizationId}) plus Spring Kafka's {@code kafka_dlt-*}
 * metadata (original topic, exception class/message) that {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}
 * added. Idempotent: it skips a DLT record it has already drained (by the record's topic/partition/offset), and the
 * unique constraint on those coordinates is the backstop. Starts only when {@code healthcloud.kafka.consumers.enabled}
 * is true (the broker-free test suite leaves it off).
 */
@Component
public class DeadLetterDrainer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterDrainer.class);

    private final DeadLetterEventRepository deadLetters;

    public DeadLetterDrainer(DeadLetterEventRepository deadLetters) {
        this.deadLetters = deadLetters;
    }

    @KafkaListener(
            topics = "claim.adjudicated.DLT",
            groupId = "dead-letter-drainer",
            autoStartup = "${healthcloud.kafka.consumers.enabled:true}")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        // Idempotency: a redelivered DLT record is identified by its own topic/partition/offset.
        if (deadLetters.existsByDltTopicAndDltPartitionAndDltOffset(
                record.topic(), record.partition(), record.offset())) {
            log.debug("Skipping already-drained dead-letter record {}-{}@{}",
                    record.topic(), record.partition(), record.offset());
            return;
        }

        UUID organizationId = uuidHeader(record, "organizationId");
        UUID eventId = uuidHeader(record, "eventId");
        String sourceTopic = stringHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (sourceTopic == null) {
            sourceTopic = record.topic().endsWith(".DLT")
                    ? record.topic().substring(0, record.topic().length() - ".DLT".length())
                    : record.topic();
        }
        String exceptionType = stringHeader(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
        String exceptionMessage = truncate(stringHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE), 1000);

        try {
            deadLetters.save(new DeadLetterEvent(organizationId, sourceTopic, record.key(), record.value(),
                    eventId, exceptionType != null ? exceptionType : "unknown", exceptionMessage,
                    record.topic(), record.partition(), record.offset()));
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent redelivery drained the same coordinates first — the unique constraint backstops the
            // pre-check. Idempotent: treat as already drained.
            log.debug("Dead-letter record {}-{}@{} already drained (unique constraint) — treating as idempotent",
                    record.topic(), record.partition(), record.offset());
        }
    }

    private static UUID uuidHeader(ConsumerRecord<String, String> record, String key) {
        String value = stringHeader(record, key);
        return value == null ? null : UUID.fromString(value);
    }

    private static String stringHeader(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
