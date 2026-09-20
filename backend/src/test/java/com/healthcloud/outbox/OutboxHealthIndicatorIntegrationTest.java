package com.healthcloud.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.organization.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 11 slice 4 — the custom {@link OutboxHealthIndicator}. Exercises the status logic against a real repository
 * (Testcontainers, no mocks — the project's data-access convention) by constructing the indicator directly with the
 * relay-enabled flag and threshold as constructor args, so the outbox relay scheduler / Kafka are never touched.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class OutboxHealthIndicatorIntegrationTest {

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Test
    void disabled_relay_reports_up_without_alarming() {
        OutboxHealthIndicator indicator = new OutboxHealthIndicator(outboxEventRepository, false, 1L);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus(), "a disabled relay never alarms — a backlog is expected");
        assertEquals("disabled", health.getDetails().get("relay"));
    }

    @Test
    void backlog_within_threshold_is_up_over_threshold_is_out_of_service() {
        // Isolation-safe: never truncate the shared outbox_event table (the rest of the suite scopes to its own
        // rows so tests can share one Testcontainers Postgres in any order). Instead work RELATIVE to the current
        // backlog and clean up only the rows this test inserts.
        long before = outboxEventRepository.countByPublishedAtIsNull();

        // Threshold sits at the current backlog: `before` pending is not > before → UP; one more → over → degraded.
        OutboxHealthIndicator indicator = new OutboxHealthIndicator(outboxEventRepository, true, before);

        Health within = indicator.health();
        assertEquals(Status.UP, within.getStatus(), "backlog at (not over) the threshold is UP");
        assertEquals(before, within.getDetails().get("pending"));
        assertEquals("enabled", within.getDetails().get("relay"));

        // Insert three pending events (over threshold) → OUT_OF_SERVICE (degraded, not DOWN).
        // outbox_event FKs organization, so use a real seeded org id (the local profile seeds two orgs).
        UUID org = organizationRepository.findAll().get(0).getId();
        List<OutboxEvent> inserted = outboxEventRepository.saveAll(List.of(
                new OutboxEvent(org, "claim", UUID.randomUUID(), "claim.adjudicated", "{}", "cid-1"),
                new OutboxEvent(org, "claim", UUID.randomUUID(), "claim.adjudicated", "{}", "cid-2"),
                new OutboxEvent(org, "claim", UUID.randomUUID(), "claim.adjudicated", "{}", "cid-3")));
        try {
            Health over = indicator.health();
            assertEquals(Status.OUT_OF_SERVICE, over.getStatus(), "a backlog over the threshold is degraded");
            assertEquals(before + 3, over.getDetails().get("pending"));
        } finally {
            // Remove only the rows we added — leave any pre-existing backlog untouched.
            outboxEventRepository.deleteAll(inserted);
        }
    }
}
