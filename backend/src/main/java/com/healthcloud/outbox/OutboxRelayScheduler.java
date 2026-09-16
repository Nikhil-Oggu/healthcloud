package com.healthcloud.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link OutboxRelay#publishPending()} on a fixed schedule (source-of-truth §Phase 8). Split from the relay
 * so the publish logic can be unit/integration-tested by calling it directly, with no dependence on timing.
 *
 * <p>Gated by {@code healthcloud.outbox.relay.enabled} (default true for local dev + prod). The test suite sets it
 * false (so no scheduled Kafka sends fire without a broker) and exercises the relay by invoking
 * {@code publishPending()} directly against a Testcontainers broker.
 */
@Component
@ConditionalOnProperty(name = "healthcloud.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${healthcloud.outbox.relay.poll-interval-ms}")
    void pollAndPublish() {
        relay.publishPending();
    }
}
