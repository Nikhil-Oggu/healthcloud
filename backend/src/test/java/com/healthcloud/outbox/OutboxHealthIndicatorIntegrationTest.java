package com.healthcloud.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.organization.OrganizationRepository;
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
        // Deterministic: clear the (platform-wide, cross-tenant) backlog, then insert a known number of pending rows.
        outboxEventRepository.deleteAll();
        OutboxHealthIndicator indicator = new OutboxHealthIndicator(outboxEventRepository, true, 1L);

        // Empty backlog → UP, pending 0.
        Health empty = indicator.health();
        assertEquals(Status.UP, empty.getStatus());
        assertEquals(0L, empty.getDetails().get("pending"));

        // Two pending events with maxPending=1 → over threshold → OUT_OF_SERVICE (degraded, not DOWN).
        // outbox_event FKs organization, so use a real seeded org id (the local profile seeds two orgs).
        UUID org = organizationRepository.findAll().get(0).getId();
        outboxEventRepository.save(
                new OutboxEvent(org, "claim", UUID.randomUUID(), "claim.adjudicated", "{}", "cid-1"));
        outboxEventRepository.save(
                new OutboxEvent(org, "claim", UUID.randomUUID(), "claim.adjudicated", "{}", "cid-2"));

        Health over = indicator.health();
        assertEquals(Status.OUT_OF_SERVICE, over.getStatus(), "a backlog over the threshold is degraded");
        assertEquals(2L, over.getDetails().get("pending"));
        assertEquals("enabled", over.getDetails().get("relay"));

        outboxEventRepository.deleteAll();
    }
}
