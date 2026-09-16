package com.healthcloud.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.KafkaTestcontainersConfiguration;
import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.adjudication.ClaimAdjudicatedEvent;
import com.healthcloud.organization.OrganizationRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * The read-side consumer (Phase 8 slice 3) against a real Testcontainers Kafka broker + Postgres. Proves the
 * {@code @KafkaListener} consumes a {@code claim.adjudicated} event into a PHI-free notification, and that a
 * redelivered event (same {@code eventId}) is idempotent — exactly one notification results. Consumers are
 * re-enabled here ({@code healthcloud.kafka.consumers.enabled=true}); the rest of the suite keeps them stopped.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "healthcloud.kafka.consumers.enabled=true")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@ActiveProfiles("local")
class ClaimAdjudicatedConsumerKafkaIntegrationTest {

    private static final String TOPIC = "claim.adjudicated";
    private static final String PATIENT_NAME = "Consumer Test Patient";

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    ClaimAdjudicationNotificationRepository notifications;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    @SuppressWarnings("rawtypes") // Boot's KafkaTemplate<?,?> — inject raw (see OutboxRelay)
    KafkaTemplate kafkaTemplate;

    @Test
    void consumes_a_claim_adjudicated_event_into_a_notification() throws Exception {
        UUID organizationId = anyOrganizationId();
        UUID claimId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(organizationId, eventId, new ClaimAdjudicatedEvent(
                claimId, "CLM-CONSUME-1", 1, "ADJUDICATED", new BigDecimal("80.00"), new BigDecimal("20.00")));

        ClaimAdjudicationNotification saved = await(eventId);
        assertEquals(organizationId, saved.getOrganizationId());
        assertEquals(claimId, saved.getClaimId());
        assertTrue(saved.getMessage().contains("CLM-CONSUME-1"), saved.getMessage());
        assertTrue(saved.getMessage().contains("plan paid $80.00"), saved.getMessage());
        // PHI-free: nothing patient-identifying travels in the event or the notification.
        assertFalse(saved.getMessage().contains(PATIENT_NAME));
    }

    @Test
    void a_redelivered_event_is_idempotent() throws Exception {
        UUID organizationId = anyOrganizationId();
        UUID claimId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ClaimAdjudicatedEvent event = new ClaimAdjudicatedEvent(
                claimId, "CLM-DUP-1", 2, "ADJUDICATED", new BigDecimal("50.00"), new BigDecimal("10.00"));

        // The same event delivered twice (at-least-once) must produce exactly one notification.
        publish(organizationId, eventId, event);
        await(eventId);
        publish(organizationId, eventId, event);

        // Give the second delivery time to be (idempotently) processed, then confirm there is still just one.
        Thread.sleep(3000);
        long count = notifications.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .filter(n -> n.getEventId().equals(eventId))
                .count();
        assertEquals(1, count, "a redelivered event must not create a second notification");
    }

    // --- helpers -------------------------------------------------------------

    private UUID anyOrganizationId() {
        List<com.healthcloud.organization.Organization> orgs = organizations.findAll();
        assertFalse(orgs.isEmpty(), "the seeder provides organizations");
        return orgs.get(0).getId();
    }

    @SuppressWarnings("unchecked") // raw KafkaTemplate.send(ProducerRecord)
    private void publish(UUID organizationId, UUID eventId, ClaimAdjudicatedEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC, event.claimId().toString(), objectMapper.writeValueAsString(event));
        record.headers().add(ClaimAdjudicatedConsumer.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(ClaimAdjudicatedConsumer.HEADER_ORGANIZATION_ID,
                organizationId.toString().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    /** Poll until the consumer has recorded the event (async), up to ~20s. */
    private ClaimAdjudicationNotification await(UUID eventId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (notifications.existsByEventId(eventId)) {
                return notifications.findAll().stream()
                        .filter(n -> n.getEventId().equals(eventId))
                        .findFirst()
                        .orElseThrow();
            }
            Thread.sleep(300);
        }
        throw new AssertionError("consumer did not record a notification for event " + eventId + " in time");
    }
}
