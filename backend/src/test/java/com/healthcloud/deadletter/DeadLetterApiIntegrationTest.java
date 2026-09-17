package com.healthcloud.deadletter;

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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Dead-letter inspection (Phase 8 slice 5) against a real embedded server + Postgres + Kafka broker. Proves a
 * message that fails processing is drained onto the DLT and into the {@code dead_letter_event} store, where an
 * ORG_ADMIN of its tenant can inspect it; a non-admin is a flat 403. Consumers (the notifier + the drainer) are
 * re-enabled here; the rest of the suite keeps them stopped.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "healthcloud.kafka.consumers.enabled=true")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@ActiveProfiles("local")
class DeadLetterApiIntegrationTest {

    private static final Pattern ORG_ID = Pattern.compile("\"organizationId\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    @Autowired
    @SuppressWarnings("rawtypes") // Boot's KafkaTemplate<?,?> — inject raw (see OutboxRelay)
    KafkaTemplate kafkaTemplate;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void a_failed_message_is_drained_and_an_admin_can_inspect_it() throws Exception {
        String adminSession = login("admin@northcare.example.org");
        UUID organizationId = orgId(adminSession);
        UUID eventId = UUID.randomUUID();
        String marker = "NOTJSON-" + UUID.randomUUID();

        // A malformed payload (with valid eventId + organizationId headers) → the consumer throws a non-retryable
        // JacksonException → DLT → the drainer stores it, attributed to this tenant.
        publish(organizationId, eventId, marker);

        HttpResponse<String> listed = awaitContains(adminSession, eventId.toString());
        assertNotNull(listed, "the drained dead-letter event is visible to its tenant's admin");
        assertTrue(listed.body().contains(marker), listed.body());
        assertTrue(listed.body().contains("\"sourceTopic\":\"claim.adjudicated\""), listed.body());
        // The failure is recorded: the listener exception class + the underlying parse message.
        assertTrue(listed.body().contains("ListenerExecutionFailedException"),
                "the failure class is recorded: " + listed.body());
        assertTrue(listed.body().contains("Unrecognized token"),
                "the underlying parse failure is recorded: " + listed.body());

        // A non-admin cannot inspect dead letters — a role-gated list is a flat 403.
        assertEquals(403, get(login("coordinator@northcare.example.org"), "/api/v1/dead-letter-events").statusCode());

        // Another tenant's admin does not see this tenant's dead letter.
        String greenAdmin = login("admin@greenvalley.example.org");
        assertTrue(!get(greenAdmin, "/api/v1/dead-letter-events").body().contains(eventId.toString()),
                "dead letters are tenant-scoped");
    }

    // --- helpers -------------------------------------------------------------

    @SuppressWarnings("unchecked") // raw KafkaTemplate.send(ProducerRecord)
    private void publish(UUID organizationId, UUID eventId, String malformedValue) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>("claim.adjudicated", UUID.randomUUID().toString(), malformedValue);
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("organizationId", organizationId.toString().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> awaitContains(String session, String needle) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get(session, "/api/v1/dead-letter-events");
            if (response.statusCode() == 200 && response.body().contains(needle)) {
                return response;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("dead-letter event containing " + needle + " did not appear in time");
    }

    private UUID orgId(String session) throws Exception {
        Matcher m = ORG_ID.matcher(get(session, "/api/v1/me").body());
        assertTrue(m.find(), "expected an organizationId in /me");
        return UUID.fromString(m.group(1));
    }

    private String login(String email) throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("email=" + email))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        for (String header : login.headers().allValues("Set-Cookie")) {
            if (header.startsWith("SESSION=")) {
                int semicolon = header.indexOf(';');
                return header.substring("SESSION=".length(), semicolon >= 0 ? semicolon : header.length());
            }
        }
        throw new AssertionError("no SESSION cookie from dev-login");
    }

    private HttpResponse<String> get(String session, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path)).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
