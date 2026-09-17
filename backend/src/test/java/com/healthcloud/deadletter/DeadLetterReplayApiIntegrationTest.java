package com.healthcloud.deadletter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.KafkaTestcontainersConfiguration;
import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.adjudication.ClaimAdjudicatedEvent;
import com.healthcloud.notification.ClaimAdjudicationNotificationRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Dead-letter replay (Phase 8 slice 6) against a real embedded server + Postgres + Kafka broker. Proves that an
 * ORG_ADMIN can re-drive a stored dead-letter record onto its source topic — so the now-working consumer processes
 * it (a {@code claim_adjudication_notification} appears) — that the record is stamped replayed, that a second replay
 * is a 409, that the action is audited, and that replay is role-gated (403) and tenant-scoped (secure 404).
 *
 * <p>The happy-path record is seeded with a <b>valid</b> payload (simulating "the bug is now fixed") straight into
 * {@code dead_letter_event} via the repository; the drain path itself is covered by {@link DeadLetterApiIntegrationTest}.
 * Consumers (the notifier + the drainer) are re-enabled here; the rest of the suite keeps them stopped.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "healthcloud.kafka.consumers.enabled=true")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@ActiveProfiles("local")
class DeadLetterReplayApiIntegrationTest {

    private static final Pattern ORG_ID = Pattern.compile("\"organizationId\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    @Autowired
    DeadLetterEventRepository deadLetters;

    @Autowired
    ClaimAdjudicationNotificationRepository notifications;

    @Autowired
    ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void replay_redrives_the_message_marks_it_replayed_and_audits() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        UUID organizationId = orgId(admin);

        UUID eventId = UUID.randomUUID();
        UUID deadLetterId = seedDeadLetter(organizationId, eventId);

        // Replay re-drives the (now-valid) message onto claim.adjudicated → the consumer records a notification.
        HttpResponse<String> replayed = replay(admin, deadLetterId);
        assertEquals(200, replayed.statusCode(), replayed.body());
        assertTrue(replayed.body().contains("\"replayedAt\":\""), "the record is stamped replayed: " + replayed.body());

        awaitNotification(eventId);

        // A second replay is refused — the record is already replayed.
        assertEquals(409, replay(admin, deadLetterId).statusCode());

        // The action is audited (DEAD_LETTER_REPLAYED) against the record.
        HttpResponse<String> audit = get(admin.session,
                "/api/v1/audit-events?resourceType=DEAD_LETTER_EVENT&resourceId=" + deadLetterId);
        assertEquals(200, audit.statusCode(), audit.body());
        assertTrue(audit.body().contains("DEAD_LETTER_REPLAYED"),
                "a replay audit event is recorded: " + audit.body());
    }

    @Test
    void replay_is_role_gated_and_tenant_scoped() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        UUID organizationId = orgId(admin);
        UUID deadLetterId = seedDeadLetter(organizationId, UUID.randomUUID());

        // A non-admin cannot replay — a role-gated action is a flat 403.
        assertEquals(403, replay(loginWithCsrf("coordinator@northcare.example.org"), deadLetterId).statusCode());

        // Another tenant's admin cannot replay this tenant's record — a secure 404 (no existence leak).
        assertEquals(404, replay(loginWithCsrf("admin@greenvalley.example.org"), deadLetterId).statusCode());
    }

    // --- helpers -------------------------------------------------------------

    /** Seed a dead-letter row carrying a valid {@link ClaimAdjudicatedEvent} payload (as if the cause is now fixed). */
    private UUID seedDeadLetter(UUID organizationId, UUID eventId) {
        UUID claimId = UUID.randomUUID();
        ClaimAdjudicatedEvent event = new ClaimAdjudicatedEvent(
                claimId, "CLM-RPY" + eventId.toString().substring(0, 5), 1, "PAID",
                new BigDecimal("120.00"), new BigDecimal("30.00"));
        String payload = objectMapper.writeValueAsString(event);
        DeadLetterEvent seeded = new DeadLetterEvent(organizationId, "claim.adjudicated", claimId.toString(),
                payload, eventId, "test.SeededFailure", "seeded for replay",
                "claim.adjudicated.DLT", 0, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        return deadLetters.save(seeded).getId();
    }

    private void awaitNotification(UUID eventId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (notifications.existsByEventId(eventId)) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("replayed event " + eventId + " did not produce a notification in time");
    }

    private HttpResponse<String> replay(Session s, UUID deadLetterId) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/dead-letter-events/" + deadLetterId + "/replay"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private record Session(String session, String xsrf) {}

    private UUID orgId(Session s) throws Exception {
        Matcher m = ORG_ID.matcher(get(s.session, "/api/v1/me").body());
        assertTrue(m.find(), "expected an organizationId in /me");
        return UUID.fromString(m.group(1));
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

    private HttpResponse<String> get(String session, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path)).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
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
