package com.healthcloud.auth;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 1 acceptance proof — <b>cross-tenant isolation at the HTTP boundary</b> (§60).
 *
 * <p>Against a REAL embedded server with the seeded two-tenant demo (NorthCare + Green Valley),
 * this proves the tenant a caller sees is derived on the backend from their session — and that a
 * browser <i>cannot</i> pick a different tenant. Each session sees only its own organization, and
 * client-supplied tenant hints (query param, header) are ignored.
 *
 * <p>Scope note: the full "fetch another tenant's business record -&gt; secure 404" test arrives in
 * Phase 2 with the first tenant-owned resource endpoint. At Phase 1 the tenant-owned surface is the
 * caller's own identity ({@code /me}), which is exactly what we assert here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class TenantIsolationIntegrationTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void each_tenant_session_sees_only_its_own_organization() throws Exception {
        // NorthCare provider -> NorthCare only, never Green Valley.
        String northBody = meBodyForSession(login("provider@northcare.example.org"));
        assertTrue(northBody.contains("NorthCare Health"), "NorthCare user should see NorthCare");
        assertFalse(northBody.contains("Green Valley"), "NorthCare user must NOT see Green Valley");

        // Green Valley admin -> Green Valley only, never NorthCare.
        String greenBody = meBodyForSession(login("admin@greenvalley.example.org"));
        assertTrue(greenBody.contains("Green Valley Clinic"), "Green Valley user should see Green Valley");
        assertFalse(greenBody.contains("NorthCare"), "Green Valley user must NOT see NorthCare");
    }

    @Test
    void client_supplied_tenant_hints_cannot_switch_the_derived_tenant() throws Exception {
        String northSession = login("provider@northcare.example.org");

        // Attempt to spoof the tenant with a query param AND a header naming the *other* org.
        // The backend derives the org from the session principal only, so both are ignored.
        HttpResponse<String> spoofed = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me?organizationId=Green%20Valley%20Clinic"))
                        .header("Cookie", "SESSION=" + northSession)
                        .header("X-Organization-Id", "Green Valley Clinic")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, spoofed.statusCode());
        assertTrue(spoofed.body().contains("NorthCare Health"),
                "tenant must still be NorthCare despite spoofed hints");
        assertFalse(spoofed.body().contains("Green Valley"),
                "client-supplied tenant hints must NOT leak the other tenant");
    }

    /** dev-login for an email, returning the SESSION cookie value. */
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

    /** GET /me with a session cookie, asserting 200, returning the response body. */
    private String meBodyForSession(String session) throws Exception {
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me"))
                        .header("Cookie", "SESSION=" + session)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode());
        return me.body();
    }

    /** Extracts a cookie value by name from a list of Set-Cookie headers. */
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
