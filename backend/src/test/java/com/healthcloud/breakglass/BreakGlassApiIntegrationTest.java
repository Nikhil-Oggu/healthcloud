package com.healthcloud.breakglass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Break-glass emergency access (Phase 7 slice 4) against a real embedded server + real Postgres. Proves an
 * unassigned provider is a secure 404 on a patient; that breaking the glass opens access to that patient (and lists
 * it) and writes a BREAK_GLASS_INVOKED audit event; that an EXPIRED grant grants nothing; that a blank reason is a
 * 400; and that break-glass never crosses the tenant boundary (a cross-tenant patient is a 404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class BreakGlassApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern ORG_ID = Pattern.compile("\"organizationId\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void breaking_the_glass_opens_access_and_writes_an_audit_event() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        // Morgan is an unassigned PROVIDER → the patient is a secure 404 for them.
        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        assertEquals(404, get(morgan.session, "/api/v1/patients/" + patientId).statusCode());

        // Break the glass → 201, then the same read succeeds.
        HttpResponse<String> broke = post(morgan, "/api/v1/break-glass",
                "{\"patientId\":\"%s\",\"reason\":\"ER: unconscious patient, need history\"}".formatted(patientId));
        assertEquals(201, broke.statusCode(), broke.body());
        assertTrue(broke.body().contains("\"active\":true"), broke.body());
        assertEquals(200, get(morgan.session, "/api/v1/patients/" + patientId).statusCode(),
                "a live break-glass grant opens access to the patient");

        // The patient now appears in Morgan's (otherwise empty) patient list.
        assertTrue(get(morgan.session, "/api/v1/patients").body().contains(patientId),
                "the break-glass patient is included in the provider's list");

        // Morgan sees the grant in their own break-glass list.
        assertTrue(get(morgan.session, "/api/v1/break-glass").body().contains(patientId));

        // An auditor sees exactly one BREAK_GLASS_INVOKED event for the patient.
        Session auditor = loginWithCsrf("auditor@northcare.example.org");
        HttpResponse<String> events =
                get(auditor.session, "/api/v1/audit-events?resourceType=PATIENT&resourceId=" + patientId);
        assertEquals(200, events.statusCode(), events.body());
        assertEquals(1, countOccurrences(events.body(), "\"action\":\"BREAK_GLASS_INVOKED\""), events.body());
        // PHI-free detail: no free-text reason leaked into the audit trail.
        assertFalse(events.body().contains("unconscious"), "the reason must not appear in the audit detail");
    }

    @Test
    void an_expired_grant_does_not_open_access() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        String morganId = meMatch(morgan.session, USER_ID);
        UUID organizationId = UUID.fromString(meMatch(morgan.session, ORG_ID));

        // Insert an already-expired grant directly (created 2h ago, expired 1h ago).
        jdbc.update("""
                INSERT INTO break_glass_grant
                  (id, organization_id, app_user_id, patient_id, reason, created_at, expires_at)
                VALUES (gen_random_uuid(), ?, ?, ?, 'expired', now() - interval '2 hours', now() - interval '1 hour')""",
                organizationId, UUID.fromString(morganId), UUID.fromString(patientId));

        assertEquals(404, get(morgan.session, "/api/v1/patients/" + patientId).statusCode(),
                "an expired break-glass grant must not open access");
    }

    @Test
    void a_blank_reason_is_rejected() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        HttpResponse<String> bad = post(morgan, "/api/v1/break-glass",
                "{\"patientId\":\"%s\",\"reason\":\"\"}".formatted(patientId));
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("VALIDATION_FAILED"), bad.body());
    }

    @Test
    void breaking_glass_for_another_tenants_patient_is_a_secure_404() throws Exception {
        Session greenCoordinator = loginWithCsrf("coordinator@greenvalley.example.org");
        String greenPatientId = firstId(createPatient(greenCoordinator).body());

        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        HttpResponse<String> cross = post(morgan, "/api/v1/break-glass",
                "{\"patientId\":\"%s\",\"reason\":\"attempted cross-tenant\"}".formatted(greenPatientId));
        assertEquals(404, cross.statusCode(), "break-glass must never cross the tenant boundary");
    }

    @Test
    void a_non_provider_cannot_break_the_glass() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        // A coordinator already has broad access and is not the relationship-gated role → 403.
        HttpResponse<String> forbidden = post(coordinator, "/api/v1/break-glass",
                "{\"patientId\":\"%s\",\"reason\":\"n/a\"}".formatted(patientId));
        assertEquals(403, forbidden.statusCode(), forbidden.body());
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"BG-%s","fullName":"Break Glass Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private String meMatch(String session, Pattern pattern) throws Exception {
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = pattern.matcher(me.body());
        assertTrue(m.find(), "expected a match in /me: " + me.body());
        return m.group(1);
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
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
