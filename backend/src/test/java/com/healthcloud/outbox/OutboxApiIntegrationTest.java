package com.healthcloud.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The transactional outbox (Phase 8 slice 1) against a real embedded server + real Postgres. Proves that
 * adjudicating a claim writes a {@code claim.adjudicated} outbox row in the SAME transaction as the adjudication
 * (both committed, so the row is present once the API call returns); that the row is pending ({@code publishedAt}
 * null) and appears in the relay's poll; that re-adjudication emits a second event for the next version; that the
 * payload is minimum-necessary and PHI-free (no patient name or date of birth); and that the row carries the
 * adjudicating tenant's {@code organizationId}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class OutboxApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern ORG_ID = Pattern.compile("\"organizationId\":\"([0-9a-fA-F-]{36})\"");

    private static final String PATIENT_NAME = "Outbox Test Patient";
    private static final String PATIENT_DOB = "1990-01-01";

    @Value("${local.server.port}")
    int port;

    @Autowired
    OutboxEventRepository outbox;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void adjudicating_a_claim_writes_a_pending_phi_free_outbox_event() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        UUID organizationId = orgId(coordinator);
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());

        List<OutboxEvent> events = outbox.findByOrganizationIdAndAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
                organizationId, OutboxService.AGGREGATE_CLAIM, UUID.fromString(claimId));
        assertEquals(1, events.size(), "one claim.adjudicated event committed with the adjudication");

        OutboxEvent event = events.get(0);
        assertEquals("claim.adjudicated", event.getEventType());
        assertEquals(organizationId, event.getOrganizationId(), "the row carries the adjudicating tenant");
        assertNull(event.getPublishedAt(), "the event is pending until the relay publishes it");
        assertTrue(event.getPayload().contains("\"adjudicationVersion\":1"), event.getPayload());
        assertTrue(event.getPayload().contains("\"outcome\":\"ADJUDICATED\""), event.getPayload());

        // Minimum-necessary, PHI-free payload (rule 5): no patient name, no date of birth.
        assertFalse(event.getPayload().contains(PATIENT_NAME), "payload must not carry the patient name");
        assertFalse(event.getPayload().contains(PATIENT_DOB), "payload must not carry the date of birth");

        // It shows up in the relay's pending backlog poll.
        assertTrue(outbox.findByPublishedAtIsNullOrderByOccurredAtAsc().stream()
                .anyMatch(e -> e.getId().equals(event.getId())), "the pending event is in the relay poll");
    }

    @Test
    void re_adjudication_emits_a_second_event() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        UUID organizationId = orgId(coordinator);
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());
        assertEquals(200, adjudicate(reviewer, claimId).statusCode()); // re-adjudicate → version 2

        List<OutboxEvent> events = outbox.findByOrganizationIdAndAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
                organizationId, OutboxService.AGGREGATE_CLAIM, UUID.fromString(claimId));
        assertEquals(2, events.size(), "each adjudication run emits its own event");
        String both = events.get(0).getPayload() + events.get(1).getPayload();
        assertTrue(both.contains("\"adjudicationVersion\":1") && both.contains("\"adjudicationVersion\":2"), both);
    }

    // --- helpers (mirror AdjudicationApiIntegrationTest) ----------------------

    private record Session(String session, String xsrf) {}

    private UUID orgId(Session s) throws Exception {
        Matcher m = ORG_ID.matcher(get(s.session, "/api/v1/me").body());
        assertTrue(m.find(), "expected an organizationId in /me");
        return UUID.fromString(m.group(1));
    }

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients",
                "{\"medicalRecordNumber\":\"OBX-%s\",\"fullName\":\"%s\",\"dateOfBirth\":\"%s\"}"
                        .formatted(UUID.randomUUID().toString().substring(0, 8), PATIENT_NAME, PATIENT_DOB));
    }

    private HttpResponse<String> createClaim(Session s, String patientId) throws Exception {
        return post(s, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"99213","units":1,"chargeAmount":150.00}]}""".formatted(patientId));
    }

    private String acceptedClaim(Session coordinator, Session reviewer, String patientId) throws Exception {
        String claimId = firstId(createClaim(coordinator, patientId).body());
        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0).statusCode());
        assertEquals(200, patchStatus(reviewer, claimId, "ACCEPTED", 1).statusCode());
        return claimId;
    }

    private String firstPlanId(Session s) throws Exception {
        return firstId(get(s.session, "/api/v1/coverage-plans").body());
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"OBX-M1\",\"effectiveFrom\":\"2020-01-01\"}"
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
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (session != null) {
            b.header("Cookie", "SESSION=" + session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
