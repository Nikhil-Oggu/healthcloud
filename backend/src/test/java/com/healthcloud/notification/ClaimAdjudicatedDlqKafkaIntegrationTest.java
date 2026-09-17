package com.healthcloud.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.KafkaTestcontainersConfiguration;
import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.adjudication.ClaimAdjudicatedEvent;
import com.healthcloud.organization.OrganizationRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer retry + dead-letter handling (Phase 8 slice 4) against a real Testcontainers broker. Proves a poison
 * message (missing the required {@code eventId} header — a non-retryable structural failure) is routed to
 * {@code claim.adjudicated.DLT} without creating a notification, and that a valid message published after it is
 * still consumed — the poison does not block the partition.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "healthcloud.kafka.consumers.enabled=true")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@ActiveProfiles("local")
class ClaimAdjudicatedDlqKafkaIntegrationTest {

    private static final String TOPIC = "claim.adjudicated";
    private static final String DLT = "claim.adjudicated.DLT";

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    ClaimAdjudicationNotificationRepository notifications;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ConfluentKafkaContainer kafka;

    @Autowired
    @SuppressWarnings("rawtypes") // Boot's KafkaTemplate<?,?> — inject raw (see OutboxRelay)
    KafkaTemplate kafkaTemplate;

    @Test
    void a_poison_message_is_routed_to_the_dead_letter_topic() throws Exception {
        UUID organizationId = anyOrganizationId();
        String poisonClaimNumber = "CLM-POISON-" + UUID.randomUUID().toString().substring(0, 8);
        ClaimAdjudicatedEvent poison = new ClaimAdjudicatedEvent(
                UUID.randomUUID(), poisonClaimNumber, 1, "ADJUDICATED",
                new BigDecimal("10.00"), new BigDecimal("0.00"));

        try (KafkaConsumer<String, String> dltConsumer = consumer()) {
            dltConsumer.subscribe(List.of(DLT));

            // Missing the eventId header → IllegalArgumentException (non-retryable) → straight to the DLT.
            publish(organizationId, null, poison);

            ConsumerRecord<String, String> dead = pollFor(dltConsumer, poisonClaimNumber);
            assertNotNull(dead, "the poison message reached the dead-letter topic");
            assertTrue(dead.value().contains(poisonClaimNumber), dead.value());
        }

        // It never became a notification.
        assertFalse(
                notifications.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                        .anyMatch(n -> n.getMessage().contains(poisonClaimNumber)),
                "a poison message must not produce a notification");
    }

    @Test
    void a_valid_message_after_a_poison_one_is_still_consumed() throws Exception {
        UUID organizationId = anyOrganizationId();
        UUID goodEventId = UUID.randomUUID();

        // A poison first (no eventId header), then a good message — the good one must still be processed.
        publish(organizationId, null, new ClaimAdjudicatedEvent(
                UUID.randomUUID(), "CLM-POISON-2", 1, "ADJUDICATED", new BigDecimal("1.00"), new BigDecimal("0.00")));
        publish(organizationId, goodEventId, new ClaimAdjudicatedEvent(
                UUID.randomUUID(), "CLM-GOOD-2", 1, "ADJUDICATED", new BigDecimal("90.00"), new BigDecimal("10.00")));

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !notifications.existsByEventId(goodEventId)) {
            Thread.sleep(300);
        }
        assertTrue(notifications.existsByEventId(goodEventId),
                "a valid message after a poison one is still consumed (partition not blocked)");
    }

    // --- helpers -------------------------------------------------------------

    private UUID anyOrganizationId() {
        List<com.healthcloud.organization.Organization> orgs = organizations.findAll();
        assertFalse(orgs.isEmpty(), "the seeder provides organizations");
        return orgs.get(0).getId();
    }

    @SuppressWarnings("unchecked") // raw KafkaTemplate.send(ProducerRecord)
    private void publish(UUID organizationId, UUID eventIdOrNull, ClaimAdjudicatedEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC, event.claimId().toString(), objectMapper.writeValueAsString(event));
        if (eventIdOrNull != null) {
            record.headers().add(ClaimAdjudicatedConsumer.HEADER_EVENT_ID,
                    eventIdOrNull.toString().getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add(ClaimAdjudicatedConsumer.HEADER_ORGANIZATION_ID,
                organizationId.toString().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private ConsumerRecord<String, String> pollFor(KafkaConsumer<String, String> consumer, String needle) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(needle)) {
                    return record;
                }
            }
        }
        return null;
    }

    private KafkaConsumer<String, String> consumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                // The DLT topic is created only when the recoverer first publishes; refresh metadata often so this
                // consumer discovers the new topic/partition promptly instead of waiting the 5-minute default.
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
