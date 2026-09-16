package com.healthcloud.identity;

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
 * Provider directory API (Phase 6 slice 21) against a real embedded server with the seeded demo. Proves the
 * resource is authenticated and tenant-scoped; that it returns the tenant's active PROVIDERs (Dana + Morgan) and
 * excludes non-providers (coordinator/reviewer); that it is gated to the claim-create roles (a PATIENT and a
 * CLAIMS_REVIEWER are 403); and that another tenant's providers never appear.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ProviderDirectoryApiIntegrationTest {

    private static final Pattern ME_USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void the_directory_requires_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/providers").statusCode());
    }

    @Test
    void it_lists_the_tenants_providers_and_excludes_non_providers() throws Exception {
        String admin = loginSession("admin@northcare.example.org");
        HttpResponse<String> list = get(admin, "/api/v1/providers");
        assertEquals(200, list.statusCode(), list.body());

        // The seeded PROVIDERs are present…
        assertTrue(list.body().contains("Dana Provider"), "Dana (provider@) should be listed: " + list.body());
        assertTrue(list.body().contains("Morgan Provider"), "Morgan (provider2@) should be listed: " + list.body());
        // …and non-providers are not.
        assertFalse(list.body().contains("Coordinator"), "the coordinator must not appear in the provider directory");
        assertFalse(list.body().contains("Reviewer"), "the reviewer must not appear in the provider directory");
    }

    @Test
    void a_coordinator_may_read_the_directory() throws Exception {
        String coordinator = loginSession("coordinator@northcare.example.org");
        assertEquals(200, get(coordinator, "/api/v1/providers").statusCode());
    }

    @Test
    void a_patient_cannot_read_the_directory() throws Exception {
        String patient = loginSession("patient@northcare.example.org");
        assertEquals(403, get(patient, "/api/v1/providers").statusCode(), "a PATIENT cannot enumerate staff");
    }

    @Test
    void a_reviewer_cannot_read_the_directory() throws Exception {
        // The gate is the claim-create roles; a CLAIMS_REVIEWER does not create claims, so it is 403 here.
        String reviewer = loginSession("reviewer@northcare.example.org");
        assertEquals(403, get(reviewer, "/api/v1/providers").statusCode());
    }

    @Test
    void the_directory_is_tenant_scoped() throws Exception {
        String northProvider = loginSession("provider@northcare.example.org");
        String northProviderId = meUserId(northProvider);

        String greenAdmin = loginSession("admin@greenvalley.example.org");
        HttpResponse<String> greenList = get(greenAdmin, "/api/v1/providers");
        assertEquals(200, greenList.statusCode(), greenList.body());
        assertFalse(greenList.body().contains(northProviderId),
                "another tenant's provider must never appear in this tenant's directory");
    }

    // --- helpers -------------------------------------------------------------

    private String loginSession(String email) throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("email=" + email))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), "dev-login should succeed for " + email);
        String session = cookie(login.headers().allValues("Set-Cookie"), "SESSION");
        assertNotNull(session);
        return session;
    }

    private String meUserId(String session) throws Exception {
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = ME_USER_ID.matcher(me.body());
        assertTrue(m.find(), "expected a userId in /me: " + me.body());
        return m.group(1);
    }

    private HttpResponse<String> get(String session, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (session != null) {
            b.header("Cookie", "SESSION=" + session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
