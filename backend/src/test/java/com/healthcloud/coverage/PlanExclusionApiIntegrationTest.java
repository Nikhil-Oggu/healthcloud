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
 * The plan-exclusion API (Phase 5 slice 4) against a real embedded server with the seeded demo. Proves that
 * exclusions are authenticated, ORG_ADMIN-gated for writes, the procedure is validated, duplicates are 409, a
 * delete removes it, and another tenant's plan is a secure 404. Each test creates its own plan so exclusions do
 * not contaminate the shared seeded plans other tests rely on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PlanExclusionApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String base(String planId) {
        return "/api/v1/coverage-plans/" + planId + "/exclusions";
    }

    @Test
    void an_admin_adds_lists_and_removes_an_exclusion() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);

        HttpResponse<String> added = post(admin, base(planId), "{\"procedureCode\":\"99213\"}");
        assertEquals(201, added.statusCode(), added.body());
        assertTrue(added.body().contains("\"code\":\"99213\""));
        String exclusionId = firstId(added.body());

        assertTrue(get(admin.session, base(planId)).body().contains("\"code\":\"99213\""), "the exclusion is listed");

        HttpResponse<String> removed = delete(admin, base(planId) + "/" + exclusionId);
        assertEquals(204, removed.statusCode());
        assertFalse(get(admin.session, base(planId)).body().contains("\"code\":\"99213\""), "removed from the list");
    }

    @Test
    void a_non_admin_cannot_add_an_exclusion() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);

        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        HttpResponse<String> forbidden = post(coordinator, base(planId), "{\"procedureCode\":\"99213\"}");
        assertEquals(403, forbidden.statusCode());
        assertTrue(forbidden.body().contains("ACCESS_DENIED"));
    }

    @Test
    void a_duplicate_exclusion_is_a_409() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        assertEquals(201, post(admin, base(planId), "{\"procedureCode\":\"99213\"}").statusCode());

        HttpResponse<String> dup = post(admin, base(planId), "{\"procedureCode\":\"99213\"}");
        assertEquals(409, dup.statusCode(), dup.body());
        assertTrue(dup.body().contains("CONFLICT"));
    }

    @Test
    void an_unknown_procedure_is_a_400() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);

        HttpResponse<String> bad = post(admin, base(planId), "{\"procedureCode\":\"ZZZZZ\"}");
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void another_tenants_plan_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(north);

        Session green = loginWithCsrf("admin@greenvalley.example.org");
        HttpResponse<String> cross = get(green.session, base(planId));
        assertEquals(404, cross.statusCode(), "another tenant's plan must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    /** Create a fresh coverage plan (ORG_ADMIN) and return its id. */
    private String createPlan(Session admin) throws Exception {
        HttpResponse<String> created = post(admin, "/api/v1/coverage-plans", """
                {"planCode":"EXCL-%s","name":"Exclusion Test Plan","planType":"PPO","deductibleAmount":1500.00,
                 "coinsuranceRate":0.2000,"copayAmount":25.00,"outOfPocketMax":6000.00}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
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
        return http.send(
                HttpRequest.newBuilder(uri(path)).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
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
