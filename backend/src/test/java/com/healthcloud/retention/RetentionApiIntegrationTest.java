package com.healthcloud.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.breakglass.BreakGlassGrantRepository;
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
 * Data retention (Phase 7 slice 8) against a real embedded server + real Postgres. Proves an ORG_ADMIN can purge
 * long-expired break-glass grants for their tenant while the tamper-evident audit trail is preserved — the
 * {@code BREAK_GLASS_INVOKED} event for a purged grant remains and the purge itself is audited as
 * {@code RETENTION_PURGED}; that recently-expired and still-live grants are left untouched; that only an admin may
 * run retention; and that a purge never crosses the tenant boundary.
 *
 * <p>A break-glass grant is created live (expires ~60 min out) via the API — so a real {@code BREAK_GLASS_INVOKED}
 * event is written — then aged into the past with a direct SQL update ({@code expires_at} is immutable through the
 * entity), exactly as the chain test tampers rows directly, to set up the long-expired precondition the purge acts on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class RetentionApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern PURGED_COUNT = Pattern.compile("\"purgedCount\":(\\d+)");

    @Value("${local.server.port}")
    int port;

    @Autowired
    BreakGlassGrantRepository grants;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void purges_a_long_expired_grant_but_keeps_the_audit_trail() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        String grantId = firstId(breakGlass(morgan, patientId, "ER: unconscious").body());
        ageGrant(grantId, 100); // expired 100 days ago — older than the 90-day retention window

        // The admin runs the retention purge.
        Session admin = loginWithCsrf("admin@northcare.example.org");
        HttpResponse<String> purge = runPurge(admin);
        assertEquals(200, purge.statusCode(), purge.body());
        assertTrue(purgedCount(purge.body()) >= 1, purge.body());

        // The operational grant (and its sensitive reason) is gone from the database.
        assertTrue(grants.findById(UUID.fromString(grantId)).isEmpty(), "the long-expired grant was purged");

        // But the immutable audit trail is preserved: the purge is audited, and the BREAK_GLASS_INVOKED event for
        // the purged grant's patient remains — the whole point of retaining the audit record beyond the data.
        assertTrue(get(admin.session, "/api/v1/audit-events").body().contains("\"action\":\"RETENTION_PURGED\""));
        HttpResponse<String> patientEvents =
                get(admin.session, "/api/v1/audit-events?resourceType=PATIENT&resourceId=" + patientId);
        assertTrue(patientEvents.body().contains("\"action\":\"BREAK_GLASS_INVOKED\""),
                "the audit event survives the purge: " + patientEvents.body());
    }

    @Test
    void keeps_recently_expired_and_still_live_grants() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session morgan = loginWithCsrf("provider2@northcare.example.org");

        String livePatient = firstId(createPatient(coordinator).body());
        String liveGrantId = firstId(breakGlass(morgan, livePatient, "ER live").body()); // expires ~60 min out

        String recentPatient = firstId(createPatient(coordinator).body());
        String recentGrantId = firstId(breakGlass(morgan, recentPatient, "ER recent").body());
        ageGrant(recentGrantId, 5); // expired only 5 days ago — well inside the 90-day window

        Session admin = loginWithCsrf("admin@northcare.example.org");
        assertEquals(200, runPurge(admin).statusCode());

        assertTrue(grants.findById(UUID.fromString(liveGrantId)).isPresent(), "a live grant is never purged");
        assertTrue(grants.findById(UUID.fromString(recentGrantId)).isPresent(),
                "a grant expired within the retention window is kept");
    }

    @Test
    void only_an_admin_can_run_retention() throws Exception {
        assertEquals(403, runPurge(loginWithCsrf("auditor@northcare.example.org")).statusCode());
        assertEquals(403, runPurge(loginWithCsrf("coordinator@northcare.example.org")).statusCode());
        assertEquals(403, runPurge(loginWithCsrf("provider2@northcare.example.org")).statusCode());
        assertEquals(200, runPurge(loginWithCsrf("admin@northcare.example.org")).statusCode());
    }

    @Test
    void a_purge_never_crosses_tenants() throws Exception {
        // A long-expired grant in NorthCare...
        Session northCoordinator = loginWithCsrf("coordinator@northcare.example.org");
        String northPatient = firstId(createPatient(northCoordinator).body());
        Session northMorgan = loginWithCsrf("provider2@northcare.example.org");
        String northGrantId = firstId(breakGlass(northMorgan, northPatient, "ER north").body());
        ageGrant(northGrantId, 100);

        // ...and a long-expired grant in Green Valley.
        Session greenCoordinator = loginWithCsrf("coordinator@greenvalley.example.org");
        String greenPatient = firstId(createPatient(greenCoordinator).body());
        Session greenMorgan = loginWithCsrf("provider2@greenvalley.example.org");
        String greenGrantId = firstId(breakGlass(greenMorgan, greenPatient, "ER green").body());
        ageGrant(greenGrantId, 100);

        // NorthCare's admin runs the purge — it removes only NorthCare's grant.
        assertEquals(200, runPurge(loginWithCsrf("admin@northcare.example.org")).statusCode());
        assertTrue(grants.findById(UUID.fromString(northGrantId)).isEmpty(), "NorthCare's old grant is purged");
        assertTrue(grants.findById(UUID.fromString(greenGrantId)).isPresent(),
                "another tenant's grant is untouched by this tenant's purge");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    /** Age a grant so it appears to have expired {@code days} days ago (expires_at is immutable via the entity). */
    private void ageGrant(String grantId, int days) {
        jdbc.update("UPDATE break_glass_grant SET expires_at = now() - CAST(? AS interval) WHERE id = CAST(? AS uuid)",
                days + " days", grantId);
    }

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"RET-%s","fullName":"Retention Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private HttpResponse<String> breakGlass(Session s, String patientId, String reason) throws Exception {
        return post(s, "/api/v1/break-glass",
                "{\"patientId\":\"%s\",\"reason\":\"%s\"}".formatted(patientId, reason));
    }

    private HttpResponse<String> runPurge(Session s) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/retention/break-glass/run"))
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

    private static int purgedCount(String json) {
        Matcher m = PURGED_COUNT.matcher(json);
        assertTrue(m.find(), "expected a purgedCount in: " + json);
        return Integer.parseInt(m.group(1));
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
