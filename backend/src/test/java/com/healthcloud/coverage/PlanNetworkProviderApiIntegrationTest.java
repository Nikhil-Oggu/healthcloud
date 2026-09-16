package com.healthcloud.coverage;

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
 * Plan network-provider API (Phase 6 slice 17) against a real embedded server with the seeded demo. Proves the
 * resource is authenticated and tenant-scoped; that an admin adds/lists/removes a plan's network providers with
 * the provider name resolved; that reads are open to any same-tenant user but writes require ORG_ADMIN; that a
 * non-PROVIDER user → 400 (no existence leak); that a duplicate → 409; that candidates exclude already-added
 * providers; and that another tenant's plan is a secure 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PlanNetworkProviderApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern FIRST_USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern ME_USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String planJson(String planCode) {
        return """
                {"planCode":"%s","name":"Network Test PPO","planType":"PPO",\
                "deductibleAmount":0.00,"coinsuranceRate":0.2000,"copayAmount":0.00}""".formatted(planCode);
    }

    /** Create a plan in the caller's tenant and return its id. */
    private String createPlan(Session admin) throws Exception {
        String planCode = "NET-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        HttpResponse<String> created = post(admin, "/api/v1/coverage-plans", planJson(planCode));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    /** The first candidate provider's userId for a plan (an active same-tenant PROVIDER not yet in the network). */
    private String firstCandidate(Session admin, String planId) throws Exception {
        HttpResponse<String> candidates =
                get(admin.session, "/api/v1/coverage-plans/" + planId + "/network-providers/candidates");
        assertEquals(200, candidates.statusCode(), candidates.body());
        Matcher m = FIRST_USER_ID.matcher(candidates.body());
        assertTrue(m.find(), "expected at least one provider candidate: " + candidates.body());
        return m.group(1);
    }

    @Test
    void network_providers_require_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/coverage-plans/" + java.util.UUID.randomUUID()
                + "/network-providers").statusCode());
    }

    @Test
    void an_admin_adds_lists_and_removes_a_network_provider() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        String providerId = firstCandidate(admin, planId);

        HttpResponse<String> added = post(admin, "/api/v1/coverage-plans/" + planId + "/network-providers",
                "{\"providerUserId\":\"" + providerId + "\"}");
        assertEquals(201, added.statusCode(), added.body());
        assertTrue(added.body().contains("\"providerUserId\":\"" + providerId + "\""));
        assertTrue(added.body().contains("\"providerName\":\""), "the provider name is resolved");
        String entryId = firstId(added.body());

        HttpResponse<String> list = get(admin.session, "/api/v1/coverage-plans/" + planId + "/network-providers");
        assertTrue(list.body().contains(providerId), "the added provider is in the network list");

        assertEquals(204, delete(admin, "/api/v1/coverage-plans/" + planId + "/network-providers/" + entryId)
                .statusCode());
        HttpResponse<String> afterRemoval =
                get(admin.session, "/api/v1/coverage-plans/" + planId + "/network-providers");
        assertFalse(afterRemoval.body().contains(providerId), "the provider is gone after removal");
    }

    @Test
    void reads_are_open_but_writes_require_admin() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        String providerId = firstCandidate(admin, planId);

        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        // A coordinator can read the (empty) network list.
        assertEquals(200, get(coordinator.session, "/api/v1/coverage-plans/" + planId + "/network-providers")
                .statusCode());
        // But cannot add — that is ORG_ADMIN only.
        HttpResponse<String> denied = post(coordinator, "/api/v1/coverage-plans/" + planId + "/network-providers",
                "{\"providerUserId\":\"" + providerId + "\"}");
        assertEquals(403, denied.statusCode(), denied.body());
    }

    @Test
    void a_non_provider_user_is_a_400() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);

        // The coordinator is a real same-tenant user but not a PROVIDER → 400 (no existence leak).
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String coordinatorId = meUserId(coordinator.session);

        HttpResponse<String> bad = post(admin, "/api/v1/coverage-plans/" + planId + "/network-providers",
                "{\"providerUserId\":\"" + coordinatorId + "\"}");
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void a_duplicate_is_a_409() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        String providerId = firstCandidate(admin, planId);
        String body = "{\"providerUserId\":\"" + providerId + "\"}";

        assertEquals(201, post(admin, "/api/v1/coverage-plans/" + planId + "/network-providers", body)
                .statusCode());
        HttpResponse<String> dup = post(admin, "/api/v1/coverage-plans/" + planId + "/network-providers", body);
        assertEquals(409, dup.statusCode(), dup.body());
        assertTrue(dup.body().contains("CONFLICT"));
    }

    @Test
    void candidates_exclude_already_added_providers() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        String providerId = firstCandidate(admin, planId);

        assertEquals(201, post(admin, "/api/v1/coverage-plans/" + planId + "/network-providers",
                "{\"providerUserId\":\"" + providerId + "\"}").statusCode());

        HttpResponse<String> candidates =
                get(admin.session, "/api/v1/coverage-plans/" + planId + "/network-providers/candidates");
        assertFalse(candidates.body().contains(providerId),
                "an already-added provider is no longer a candidate");
    }

    @Test
    void fetching_another_tenants_plan_network_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(north);

        String green = loginWithCsrf("admin@greenvalley.example.org").session;
        HttpResponse<String> cross = get(green, "/api/v1/coverage-plans/" + planId + "/network-providers");
        assertEquals(404, cross.statusCode(), "another tenant's plan must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

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
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrf = cookie(me.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
        assertNotNull(xsrf);
        return new Session(session, xsrf);
    }

    private String meUserId(String session) throws Exception {
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = ME_USER_ID.matcher(me.body());
        assertTrue(m.find(), "expected a userId in /me: " + me.body());
        return m.group(1);
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

    private HttpResponse<String> delete(Session s, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .DELETE()
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
