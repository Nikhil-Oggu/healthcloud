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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Access review of break-glass (Phase 7 slice 6) against a real embedded server + real Postgres. Proves an
 * admin/auditor can see every live grant in the tenant (with the provider's resolved name); that only AUDITOR/
 * ORG_ADMIN may read the oversight list; that an ORG_ADMIN can revoke a grant early — cutting off the provider's
 * access immediately, dropping it from the list, and writing a BREAK_GLASS_REVOKED audit event; that an auditor
 * cannot revoke; and that revoking an already-revoked or cross-tenant grant is a 409 / secure 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class BreakGlassReviewApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void an_admin_sees_and_can_revoke_a_grant_cutting_off_access() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        String grantId = firstId(breakGlass(morgan, patientId, "ER: unconscious").body());
        assertEquals(200, get(morgan.session, "/api/v1/patients/" + patientId).statusCode());

        // The admin's access-review list includes the grant, naming the provider.
        Session admin = loginWithCsrf("admin@northcare.example.org");
        HttpResponse<String> all = get(admin.session, "/api/v1/break-glass/all");
        assertEquals(200, all.statusCode(), all.body());
        assertTrue(all.body().contains(grantId), all.body());
        assertTrue(all.body().contains("Morgan Provider"), "the provider name is resolved: " + all.body());

        // The admin revokes it → Morgan loses access immediately, and it drops off the list.
        assertEquals(200, revoke(admin, grantId).statusCode());
        assertEquals(404, get(morgan.session, "/api/v1/patients/" + patientId).statusCode(),
                "revocation cuts off access at once");
        assertFalse(get(admin.session, "/api/v1/break-glass/all").body().contains(grantId));

        // The revocation is audited.
        Session auditor = loginWithCsrf("auditor@northcare.example.org");
        HttpResponse<String> events =
                get(auditor.session, "/api/v1/audit-events?resourceType=PATIENT&resourceId=" + patientId);
        assertTrue(events.body().contains("\"action\":\"BREAK_GLASS_REVOKED\""), events.body());
    }

    @Test
    void the_oversight_list_is_gated_to_auditor_and_admin() throws Exception {
        assertEquals(200, get(loginSession("auditor@northcare.example.org"), "/api/v1/break-glass/all").statusCode());
        assertEquals(200, get(loginSession("admin@northcare.example.org"), "/api/v1/break-glass/all").statusCode());
        // A provider or coordinator cannot review the tenant's emergency access.
        assertEquals(403, get(loginSession("provider2@northcare.example.org"), "/api/v1/break-glass/all").statusCode());
        assertEquals(403, get(loginSession("coordinator@northcare.example.org"), "/api/v1/break-glass/all").statusCode());
    }

    @Test
    void an_auditor_cannot_revoke() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        String grantId = firstId(breakGlass(morgan, patientId, "ER").body());

        // The auditor is read-only; revocation is an administrative action.
        Session auditor = loginWithCsrf("auditor@northcare.example.org");
        assertEquals(403, revoke(auditor, grantId).statusCode());
    }

    @Test
    void revoking_an_already_revoked_grant_is_a_conflict() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        String grantId = firstId(breakGlass(morgan, patientId, "ER").body());

        Session admin = loginWithCsrf("admin@northcare.example.org");
        assertEquals(200, revoke(admin, grantId).statusCode());
        HttpResponse<String> again = revoke(admin, grantId);
        assertEquals(409, again.statusCode(), again.body());
        assertTrue(again.body().contains("INVALID_STATE_TRANSITION"), again.body());
    }

    @Test
    void revoking_another_tenants_grant_is_a_secure_404() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        Session morgan = loginWithCsrf("provider2@northcare.example.org");
        String grantId = firstId(breakGlass(morgan, patientId, "ER").body());

        Session greenAdmin = loginWithCsrf("admin@greenvalley.example.org");
        assertEquals(404, revoke(greenAdmin, grantId).statusCode(), "another tenant's grant is a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"BGR-%s","fullName":"Break Glass Review Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private HttpResponse<String> breakGlass(Session s, String patientId, String reason) throws Exception {
        return post(s, "/api/v1/break-glass",
                "{\"patientId\":\"%s\",\"reason\":\"%s\"}".formatted(patientId, reason));
    }

    private HttpResponse<String> revoke(Session s, String grantId) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/break-glass/" + grantId + "/revoke"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String loginSession(String email) throws Exception {
        return loginWithCsrf(email).session;
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
