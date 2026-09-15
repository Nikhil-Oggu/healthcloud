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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Coverage plan API (Phase 4 slice 5) against a real embedded server with the seeded demo. Proves the resource
 * is authenticated and tenant-scoped, that only an ORG_ADMIN may create a plan, that a duplicate code is a 409,
 * and that another tenant's plan is a secure 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class CoveragePlanApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String planJson(String code) {
        return """
                {"planCode":"%s","name":"Test Plan","planType":"PPO","deductibleAmount":1500.00,
                 "coinsuranceRate":0.2000,"copayAmount":25.00,"outOfPocketMax":6000.00}""".formatted(code);
    }

    @Test
    void coverage_plans_require_authentication() throws Exception {
        HttpResponse<String> anon = get(null, "/api/v1/coverage-plans");
        assertEquals(401, anon.statusCode());
    }

    @Test
    void an_admin_can_create_and_read_a_plan() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String code = "PPO-" + UUID.randomUUID().toString().substring(0, 8);

        HttpResponse<String> created = post(admin, "/api/v1/coverage-plans", planJson(code));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains(code));
        assertTrue(created.body().contains("\"coinsuranceRate\":0.2"));
        String id = firstId(created.body());

        HttpResponse<String> read = get(admin.session, "/api/v1/coverage-plans/" + id);
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains(code));

        // The seeded plans are also visible in the tenant's list.
        assertTrue(get(admin.session, "/api/v1/coverage-plans").body().contains("NC-PPO-STD"));
    }

    @Test
    void a_non_admin_cannot_create_a_plan() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        HttpResponse<String> created = post(coordinator, "/api/v1/coverage-plans", planJson("PPO-NOPE"));
        assertEquals(403, created.statusCode());
        assertTrue(created.body().contains("ACCESS_DENIED"));
    }

    @Test
    void a_duplicate_plan_code_is_a_conflict() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String code = "DUP-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, post(admin, "/api/v1/coverage-plans", planJson(code)).statusCode());

        HttpResponse<String> dup = post(admin, "/api/v1/coverage-plans", planJson(code));
        assertEquals(409, dup.statusCode());
        assertTrue(dup.body().contains("CONFLICT"));
    }

    @Test
    void fetching_another_tenants_plan_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("admin@northcare.example.org");
        String id = firstId(post(north, "/api/v1/coverage-plans",
                planJson("X-" + UUID.randomUUID().toString().substring(0, 8))).body());

        Session green = loginWithCsrf("admin@greenvalley.example.org");
        HttpResponse<String> cross = get(green.session, "/api/v1/coverage-plans/" + id);
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
