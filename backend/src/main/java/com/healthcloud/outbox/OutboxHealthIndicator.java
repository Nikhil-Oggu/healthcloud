package com.healthcloud.outbox;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * A custom domain health indicator for the transactional-outbox publishing pipeline (Phase 11 slice 4).
 * Surfaced as the {@code outbox} component under {@code /actuator/health}, it reports whether the relay is
 * keeping up: the pending (unpublished) backlog size and the age of the oldest pending event.
 *
 * <p><b>It contributes to the root health aggregate only — deliberately NOT to the readiness probe.</b> A relay
 * backlog does not stop the app from serving user requests, so it must not pull the pod out of the load balancer
 * (that is what readiness is for). This indicator is an operator/monitoring signal instead: a big or stale backlog
 * turns the root {@code /actuator/health} to {@code OUT_OF_SERVICE} (a 503 an alert can scrape) without killing or
 * de-pooling anything. The container/liveness health check targets {@code /actuator/health/liveness}, so this can
 * never trigger a restart either.
 *
 * <p>Details are PHI-free (counts + ages only, rule 5) — never an event payload, aggregate id, or correlation id.
 *
 * <p>When the relay is disabled ({@code healthcloud.outbox.relay.enabled=false}, e.g. the deployed {@code
 * demo,cognito} app with no MSK, or the test suite) nothing publishes, so a backlog is expected and meaningless —
 * the indicator reports {@code UP} with {@code relay: disabled} rather than alarming.
 */
@Component
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxEventRepository outboxEventRepository;
    private final boolean relayEnabled;
    private final long maxPending;

    public OutboxHealthIndicator(
            OutboxEventRepository outboxEventRepository,
            @Value("${healthcloud.outbox.relay.enabled:true}") boolean relayEnabled,
            @Value("${healthcloud.outbox.health.max-pending:500}") long maxPending) {
        this.outboxEventRepository = outboxEventRepository;
        this.relayEnabled = relayEnabled;
        this.maxPending = maxPending;
    }

    @Override
    public Health health() {
        if (!relayEnabled) {
            // Nothing is publishing, so a pending backlog is expected and not a problem to alert on.
            return Health.up().withDetail("relay", "disabled").build();
        }

        long pending = outboxEventRepository.countByPublishedAtIsNull();
        long oldestAgeSeconds = outboxEventRepository.findFirstByPublishedAtIsNullOrderByOccurredAtAsc()
                .map(OutboxEvent::getOccurredAt)
                .map(occurredAt -> Math.max(0, Duration.between(occurredAt, OffsetDateTime.now()).getSeconds()))
                .orElse(0L);

        Status status = pending > maxPending ? Status.OUT_OF_SERVICE : Status.UP;
        return Health.status(status)
                .withDetail("relay", "enabled")
                .withDetail("pending", pending)
                .withDetail("maxPending", maxPending)
                .withDetail("oldestPendingAgeSeconds", oldestAgeSeconds)
                .build();
    }
}
