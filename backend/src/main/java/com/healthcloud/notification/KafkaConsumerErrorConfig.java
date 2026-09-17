package com.healthcloud.notification;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * Consumer retry + dead-letter handling (source-of-truth §Phase 8 slice 4). Spring Boot applies a single
 * {@link DefaultErrorHandler} bean to the auto-configured listener factory, so this is all the wiring the
 * {@code @KafkaListener} consumers need.
 *
 * <p>On a listener failure the record is retried a bounded number of times with a fixed backoff; once retries are
 * exhausted the {@link DeadLetterPublishingRecoverer} republishes it to {@code <topic>.DLT} (e.g.
 * {@code claim.adjudicated.DLT}), preserving key/value/headers and adding exception metadata — so a stuck record
 * never blocks the partition. <b>Structural failures are not retried</b>: a missing/invalid header
 * ({@link IllegalArgumentException}) or a malformed payload ({@link JacksonException}) can never succeed on retry,
 * so they go straight to the DLT. The idempotent consumer (slice 3) makes the retries safe.
 *
 * <p><b>Honest limitation:</b> inspecting or re-driving the DLT (and the broader outbox replay) is a later slice —
 * this slice only parks failures there.
 */
@Configuration
public class KafkaConsumerErrorConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaOperations<?, ?> kafkaOperations,
            @Value("${healthcloud.kafka.consumers.retry.max-attempts}") int maxAttempts,
            @Value("${healthcloud.kafka.consumers.retry.backoff-ms}") long backoffMs) {
        // Republish to <topic>.DLT once retries are exhausted. Partition -1 lets the broker choose, so the DLT
        // (auto-created by the broker) needs no matching partition count.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        // FixedBackOff's second arg is the number of RETRIES after the first failure, so total attempts = retries + 1.
        FixedBackOff backOff = new FixedBackOff(backoffMs, Math.max(0, maxAttempts - 1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // A structurally-bad record can never succeed on retry — send it to the DLT immediately.
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
        return handler;
    }
}
