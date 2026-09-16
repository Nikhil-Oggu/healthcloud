package com.healthcloud.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.KafkaTestcontainersConfiguration;
import com.healthcloud.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * The outbox relay against a real embedded server, real Postgres, and a real Kafka broker (Phase 8 slice 2).
 * Proves that {@code OutboxRelay.publishPending()} publishes a pending {@code claim.adjudicated} event to Kafka
 * (keyed by the claim id, payload as value, metadata in headers), stamps the outbox row {@code published_at}, and
 * does not re-publish an already-published row. The relay is invoked directly (not via the scheduler) for a
 * deterministic test — the scheduler is disabled across the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@ActiveProfiles("local")
class OutboxRelayKafkaIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final String TOPIC = "claim.adjudicated";

    @Value("${local.server.port}")
    int port;

    // Read the broker address from the container itself — @ServiceConnection wires Spring via a ConnectionDetails
    // bean (not the spring.kafka.bootstrap-servers property), so the property still holds its application.yml default.
    @Autowired
    ConfluentKafkaContainer kafka;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxEventRepository outbox;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void the_relay_publishes_a_pending_event_to_kafka_and_marks_it_published() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());

        // The event is pending before the relay runs.
        OutboxEvent pending = outbox
                .findByOrganizationIdAndAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
                        orgId(coordinator), OutboxService.AGGREGATE_CLAIM, UUID.fromString(claimId))
                .get(0);
        assertNotNull(pending);

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(TOPIC));

            int published = relay.publishPending();
            assertTrue(published >= 1, "the relay published the pending event");

            ConsumerRecord<String, String> record = poll(consumer, claimId);
            assertNotNull(record, "a claim.adjudicated message reached Kafka");
            assertEquals(claimId, record.key(), "keyed by the claim (aggregate) id");
            assertTrue(record.value().contains("\"adjudicationVersion\":1"), record.value());
            assertEquals(orgId(coordinator).toString(), header(record, "organizationId"),
                    "the tenant travels in a header");
            assertEquals(TOPIC, header(record, "eventType"));

            // The outbox row is now marked published, and a second run publishes nothing new.
            assertNotNull(outbox.findById(pending.getId()).orElseThrow().getPublishedAt(),
                    "published_at is stamped");
            assertEquals(0, relay.publishPending(), "an already-published row is not re-sent");
        }
    }

    private ConsumerRecord<String, String> poll(KafkaConsumer<String, String> consumer, String claimId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (claimId.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    private KafkaConsumer<String, String> consumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private static String header(ConsumerRecord<String, String> record, String key) {
        var h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // --- helpers (mirror the adjudication flow) -------------------------------

    private record Session(String session, String xsrf) {}

    private UUID orgId(Session s) throws Exception {
        Matcher m = Pattern.compile("\"organizationId\":\"([0-9a-fA-F-]{36})\"").matcher(get(s.session, "/api/v1/me").body());
        assertTrue(m.find());
        return UUID.fromString(m.group(1));
    }

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"RLY-%s","fullName":"Relay Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private String acceptedClaim(Session coordinator, Session reviewer, String patientId) throws Exception {
        String claimId = firstId(post(coordinator, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"99213","units":1,"chargeAmount":150.00}]}""".formatted(patientId)).body());
        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0).statusCode());
        assertEquals(200, patchStatus(reviewer, claimId, "ACCEPTED", 1).statusCode());
        return claimId;
    }

    private String firstPlanId(Session s) throws Exception {
        return firstId(get(s.session, "/api/v1/coverage-plans").body());
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"RLY-M1\",\"effectiveFrom\":\"2020-01-01\"}"
                        .formatted(planId));
        assertEquals(201, enrolled.statusCode(), enrolled.body());
    }

    private HttpResponse<String> patchStatus(Session s, String claimId, String target, long expectedVersion)
            throws Exception {
        String json = "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion);
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/claims/" + claimId + "/status"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> adjudicate(Session s, String claimId) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/claims/" + claimId + "/adjudicate"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Session loginWithCsrf(String email) throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("email=" + email))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        String session = cookie(login.headers().allValues("Set-Cookie"), "SESSION");
        assertNotNull(session);
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrf = cookie(me.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
        assertNotNull(xsrf);
        return new Session(session, xsrf);
    }

    private HttpResponse<String> post(Session s, String path, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String session, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path)).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String firstId(String json) {
        Matcher m = FIRST_ID.matcher(json);
        assertTrue(m.find(), "expected an id in: " + json);
        return m.group(1);
    }

    private static String cookie(List<String> setCookies, String name) {
        String prefix = name + "=";
        for (String header : setCookies) {
            if (header.startsWith(prefix)) {
                int semicolon = header.indexOf(';');
                return header.substring(prefix.length(), semicolon >= 0 ? semicolon : header.length());
            }
        }
        return null;
    }
}
