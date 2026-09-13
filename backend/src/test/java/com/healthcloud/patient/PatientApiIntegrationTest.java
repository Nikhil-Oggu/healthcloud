package com.healthcloud.patient;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 2 acceptance proof — <b>cross-tenant isolation for a real business resource</b>. Patient is
 * the first tenant-owned business entity, so this is the "fetch another tenant's record → secure
 * 404" test that Phase 1 slice 7 deferred (there was no such resource yet).
 *
 * <p>Against a real embedded server with the seeded two-tenant demo: each tenant lists only its own
 * patients, and a caller requesting another tenant's patient by id gets <b>404</b> (existence hidden),
 * not 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PatientApiIntegrationTest {

    private static final Pattern FIRST_ID =
            Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void each_tenant_lists_only_its_own_patients() throws Exception {
        String northList = getBody(login("provider@northcare.example.org"), "/api/v1/patients", 200);
        assertTrue(northList.contains("NC-0001"), "NorthCare should see its own patients");
        assertFalse(northList.contains("GV-"), "NorthCare must NOT see Green Valley patients");

        String greenList = getBody(login("admin@greenvalley.example.org"), "/api/v1/patients", 200);
        assertTrue(greenList.contains("GV-0001"), "Green Valley should see its own patients");
        assertFalse(greenList.contains("NC-"), "Green Valley must NOT see NorthCare patients");
    }

    @Test
    void fetching_another_tenants_patient_by_id_returns_secure_404() throws Exception {
        String northSession = login("provider@northcare.example.org");
        String greenSession = login("admin@greenvalley.example.org");

        // A patient id that genuinely exists — but in the OTHER tenant.
        String greenPatientId = firstId(getBody(greenSession, "/api/v1/patients", 200));
        String northPatientId = firstId(getBody(northSession, "/api/v1/patients", 200));

        // Own-tenant read works...
        HttpResponse<String> own = get(northSession, "/api/v1/patients/" + northPatientId);
        assertEquals(200, own.statusCode());

        // ...but the other tenant's real patient id is reported as NOT FOUND, never 403 (no existence leak).
        HttpResponse<String> cross = get(northSession, "/api/v1/patients/" + greenPatientId);
        assertEquals(404, cross.statusCode(), "cross-tenant patient must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"), "error code should be NOT_FOUND");
        assertFalse(cross.body().contains("Green"), "the 404 must not leak the other tenant's data");
    }

    @Test
    void patients_require_authentication() throws Exception {
        HttpResponse<String> anon = get(null, "/api/v1/patients");
        assertEquals(401, anon.statusCode());
    }

    // --- helpers -------------------------------------------------------------

    private String login(String email) throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("email=" + email))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), "dev-login should succeed for " + email);
        String session = cookie(login.headers().allValues("Set-Cookie"), "SESSION");
        assertNotNull(session, "dev-login should set a SESSION cookie");
        return session;
    }

    private HttpResponse<String> get(String session, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (session != null) {
            b.header("Cookie", "SESSION=" + session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String getBody(String session, String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = get(session, path);
        assertEquals(expectedStatus, response.statusCode(), "unexpected status for " + path);
        return response.body();
    }

    private static String firstId(String json) {
        Matcher m = FIRST_ID.matcher(json);
        assertTrue(m.find(), "expected at least one patient id in: " + json);
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
