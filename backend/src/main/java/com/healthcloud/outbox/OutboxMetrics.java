package com.healthcloud.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Publishes the transactional-outbox backlog as a Micrometer gauge (Phase 11 slice 5), so the pipeline health that
 * slice 4's {@link OutboxHealthIndicator} surfaces on {@code /actuator/health} is also a first-class <b>metric</b>
 * on {@code /actuator/prometheus} — and therefore <b>alertable</b> (the {@code OutboxBacklogHigh} Prometheus rule).
 *
 * <p>{@code healthcloud.outbox.pending} renders as {@code healthcloud_outbox_pending}: the number of unpublished
 * outbox events. The gauge supplier runs a cheap partial-index-backed COUNT at each scrape (~15s) — PHI-free
 * (a count only, rule 5). A gauge (not a counter) because the backlog goes up and down as the relay drains it.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        Gauge.builder("healthcloud.outbox.pending", outboxEventRepository,
                        repo -> (double) repo.countByPublishedAtIsNull())
                .description("Number of outbox events not yet published to Kafka (the relay backlog)")
                .register(meterRegistry);
    }
}
