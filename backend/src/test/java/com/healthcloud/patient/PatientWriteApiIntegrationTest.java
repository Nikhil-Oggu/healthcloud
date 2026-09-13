package com.healthcloud.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 2 slice 2 — the patient <b>write path</b> against a real server: create/update with backend
 * role authorization, tenant stamping, duplicate/optimistic-lock conflicts, validation, and CSRF
 * (POST/PATCH are state-changing, so each write does the readable-cookie → header handshake).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PatientWriteApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void coordinator_can_create_a_patient_and_it_appears_in_the_tenant_list() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");

        HttpResponse<String> created = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"NC-T100","fullName":"Written Patient","dateOfBirth":"1980-05-05"}""");
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("NC-T100"));

        HttpResponse<String> list = get(s.session, "/api/v1/patients");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("Written Patient"), "created patient should show in the list");
    }

    @Test
    void provider_cannot_create_a_patient() throws Exception {
        Session s = loginWithCsrf("provider@northcare.example.org"); // PROVIDER lacks a write role
        HttpResponse<String> created = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"NC-T200","fullName":"Should Fail","dateOfBirth":"1980-05-05"}""");
        assertEquals(403, created.statusCode());
        assertTrue(created.body().contains("ACCESS_DENIED"));
    }

    @Test
    void duplicate_mrn_in_the_same_tenant_is_a_conflict() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");
        String body = """
                {"medicalRecordNumber":"NC-T300","fullName":"First","dateOfBirth":"1980-05-05"}""";
        assertEquals(201, post(s, "/api/v1/patients", body).statusCode());

        HttpResponse<String> dup = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"NC-T300","fullName":"Second","dateOfBirth":"1981-06-06"}""");
        assertEquals(409, dup.statusCode());
        assertTrue(dup.body().contains("CONFLICT"));
    }

    @Test
    void invalid_payloads_are_rejected_with_400() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");

        HttpResponse<String> blankName = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"NC-T400","fullName":"","dateOfBirth":"1980-05-05"}""");
        assertEquals(400, blankName.statusCode());
        assertTrue(blankName.body().contains("VALIDATION_FAILED"));

        HttpResponse<String> futureDob = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"NC-T401","fullName":"Future Baby","dateOfBirth":"2999-01-01"}""");
        assertEquals(400, futureDob.statusCode());
    }

    @Test
    void update_uses_optimistic_locking() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");

        HttpResponse<String> created = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"NC-T500","fullName":"Before","dateOfBirth":"1980-05-05"}""");
        assertEquals(201, created.statusCode());
        String id = firstId(created.body());
        long version = version(created.body());

        // Correct version → success.
        HttpResponse<String> ok = patch(s, "/api/v1/patients/" + id,
                "{\"fullName\":\"After\",\"status\":\"ACTIVE\",\"expectedVersion\":" + version + "}");
        assertEquals(200, ok.statusCode());
        assertTrue(ok.body().contains("After"));

        // Re-using the now-stale version → 409.
        HttpResponse<String> stale = patch(s, "/api/v1/patients/" + id,
                "{\"fullName\":\"Again\",\"status\":\"ACTIVE\",\"expectedVersion\":" + version + "}");
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("CONFLICT"));
    }

    @Test
    void cannot_update_another_tenants_patient() throws Exception {
        Session green = loginWithCsrf("admin@greenvalley.example.org");
        String greenPatientId = firstId(get(green.session, "/api/v1/patients").body());

        Session north = loginWithCsrf("coordinator@northcare.example.org");
        HttpResponse<String> cross = patch(north, "/api/v1/patients/" + greenPatientId,
                "{\"fullName\":\"Hijack\",\"status\":\"ACTIVE\",\"expectedVersion\":0}");
        assertEquals(404, cross.statusCode(), "cross-tenant update must be a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    /** A logged-in session plus its readable CSRF token (needed for state-changing requests). */
    private record Session(String session, String xsrf) {}

    private Session loginWithCsrf(String email) throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("email=" + email))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), "dev-login should succeed for " + email);
        String session = cookie(login.headers().allValues("Set-Cookie"), "SESSION");
        assertNotNull(session);

        // GET /me issues the XSRF-TOKEN cookie the SPA (and this test) echoes back as a header.
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrf = cookie(me.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
        assertNotNull(xsrf, "an XSRF-TOKEN cookie should be issued");
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

    private HttpResponse<String> patch(Session s, String path, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
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

    private static long version(String json) {
        Matcher m = VERSION.matcher(json);
        assertTrue(m.find(), "expected a version in: " + json);
        return Long.parseLong(m.group(1));
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
