package com.healthcloud.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.healthcloud.TestcontainersConfiguration;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 11 slice 5 — the {@code healthcloud.outbox.pending} gauge ({@link OutboxMetrics}). Proves the backlog is a
 * real Micrometer metric (so the {@code OutboxBacklogHigh} Prometheus alert can fire on it) and that its value
 * tracks the repository count. Uses the real registry + repository (Testcontainers, no mocks).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class OutboxMetricsIntegrationTest {

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Test
    void outbox_pending_gauge_is_registered_and_tracks_the_backlog() {
        Gauge gauge = meterRegistry.find("healthcloud.outbox.pending").gauge();
        assertNotNull(gauge, "the healthcloud.outbox.pending gauge must be registered");

        assertEquals((double) outboxEventRepository.countByPublishedAtIsNull(), gauge.value(), 0.0001,
                "the gauge must reflect the current unpublished-event count");
    }
}
