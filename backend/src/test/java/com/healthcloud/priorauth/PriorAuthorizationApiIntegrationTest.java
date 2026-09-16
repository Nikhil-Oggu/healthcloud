package com.healthcloud.priorauth;

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
 * Prior-authorization API (Phase 6 slice 1) against a real embedded server with the seeded demo. Proves the
 * resource is authenticated and tenant-scoped, that a request is created REQUESTED with a backend-allocated
 * number, that the procedure and coverage plan are validated (→ 400), that the decision lifecycle is enforced
 * (reviewer approves/denies with the reason rule; requester cancels; a non-reviewer cannot decide; illegal
 * moves and stale versions are 409), and that the object/relationship gate applies (a provider sees only
 * assigned patients' authorizations; another tenant is a secure 404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PriorAuthorizationApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern AUTH_NUMBER = Pattern.compile("\"authNumber\":\"([^\"]+)\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String authJson(String patientId, String planId) {
        return """
                {"patientId":"%s","coveragePlanId":"%s","procedureCode":"99213",
                 "requestedServiceFrom":"2026-06-01","requestedServiceTo":"2026-06-30"}"""
                .formatted(patientId, planId);
    }

    @Test
    void prior_authorizations_require_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/prior-authorizations").statusCode());
    }

    @Test
    void request_creates_a_requested_authorization() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);

        HttpResponse<String> created = post(coordinator, "/api/v1/prior-authorizations", authJson(patientId, planId));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"status\":\"REQUESTED\""), "a new authorization is REQUESTED");
        assertTrue(created.body().contains("\"authNumber\":\"PA-"), "an auth number is allocated");
        assertTrue(created.body().contains("\"procedureCode\":\"99213\""), "the requested procedure is present");
    }

    @Test
    void a_reviewer_approves_a_requested_authorization() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);
        String authId = firstId(post(coordinator, "/api/v1/prior-authorizations", authJson(patientId, planId)).body());

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        HttpResponse<String> approved = patchStatus(reviewer, authId, "APPROVED", 0, null);
        assertEquals(200, approved.statusCode(), approved.body());
        assertTrue(approved.body().contains("\"status\":\"APPROVED\""), "the authorization is approved");
        assertTrue(approved.body().contains("\"decidedBy\":\""), "the reviewer is stamped on the decision");
    }

    @Test
    void denying_requires_a_reason() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);
        String authId = firstId(post(coordinator, "/api/v1/prior-authorizations", authJson(patientId, planId)).body());

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        // No reason → 400.
        assertEquals(400, patchStatus(reviewer, authId, "DENIED", 0, null).statusCode());
        // With a reason → 200 DENIED.
        HttpResponse<String> denied = patchStatus(reviewer, authId, "DENIED", 0, "Not medically necessary");
        assertEquals(200, denied.statusCode(), denied.body());
        assertTrue(denied.body().contains("\"status\":\"DENIED\""));
    }

    @Test
    void a_non_reviewer_cannot_decide() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);
        String authId = firstId(post(coordinator, "/api/v1/prior-authorizations", authJson(patientId, planId)).body());

        // A coordinator may request but not approve — the decision is the reviewer's action (403).
        assertEquals(403, patchStatus(coordinator, authId, "APPROVED", 0, null).statusCode());
    }

    @Test
    void the_requester_can_cancel() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);
        String authId = firstId(post(coordinator, "/api/v1/prior-authorizations", authJson(patientId, planId)).body());

        HttpResponse<String> cancelled = patchStatus(coordinator, authId, "CANCELLED", 0, "Duplicate request");
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertTrue(cancelled.body().contains("\"status\":\"CANCELLED\""));
    }

    @Test
    void an_unknown_procedure_code_is_a_400() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);

        HttpResponse<String> unknown = post(coordinator, "/api/v1/prior-authorizations", """
                {"patientId":"%s","coveragePlanId":"%s","procedureCode":"00000",
                 "requestedServiceFrom":"2026-06-01"}""".formatted(patientId, planId));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertTrue(unknown.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void an_unknown_plan_is_a_400() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        HttpResponse<String> badPlan = post(coordinator, "/api/v1/prior-authorizations",
                authJson(patientId, UUID.randomUUID().toString()));
        assertEquals(400, badPlan.statusCode(), badPlan.body());
        assertTrue(badPlan.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void an_illegal_transition_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);
        String authId = firstId(post(coordinator, "/api/v1/prior-authorizations", authJson(patientId, planId)).body());

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        assertEquals(200, patchStatus(reviewer, authId, "APPROVED", 0, null).statusCode());
        // APPROVED is terminal — approving again is an illegal move (409), not a 404/500.
        HttpResponse<String> again = patchStatus(reviewer, authId, "APPROVED", 1, null);
        assertEquals(409, again.statusCode(), again.body());
        assertTrue(again.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_stale_version_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);
        String authId = firstId(post(coordinator, "/api/v1/prior-authorizations", authJson(patientId, planId)).body());

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        // Wrong expectedVersion (5, not 0) → optimistic-lock conflict.
        HttpResponse<String> stale = patchStatus(reviewer, authId, "APPROVED", 5, null);
        assertEquals(409, stale.statusCode(), stale.body());
        assertTrue(stale.body().contains("CONFLICT"));
    }

    @Test
    void a_provider_sees_only_authorizations_for_assigned_patients() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstCoveragePlanId(coordinator);
        String createdBody = post(coordinator, "/api/v1/prior-authorizations", authJson(patientId, planId)).body();
        String authNumber = authNumber(createdBody);
        String authId = firstId(createdBody);

        String provider = loginWithCsrf("provider@northcare.example.org").session;
        HttpResponse<String> list = get(provider, "/api/v1/prior-authorizations");
        assertFalse(list.body().contains(authNumber),
                "an unassigned patient's authorization is not in the provider's queue");
        // A direct read of it is a secure 404 (no existence leak).
        assertEquals(404, get(provider, "/api/v1/prior-authorizations/" + authId).statusCode());
    }

    @Test
    void fetching_another_tenants_authorization_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(north).body());
        String planId = firstCoveragePlanId(north);
        String authId = firstId(post(north, "/api/v1/prior-authorizations", authJson(patientId, planId)).body());

        String green = loginWithCsrf("admin@greenvalley.example.org").session;
        HttpResponse<String> cross = get(green, "/api/v1/prior-authorizations/" + authId);
        assertEquals(404, cross.statusCode(), "another tenant's authorization must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"PA-%s","fullName":"Prior Auth Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    /** The first seeded coverage plan id in the caller's tenant (plans are seeded under the local profile). */
    private String firstCoveragePlanId(Session s) throws Exception {
        HttpResponse<String> plans = get(s.session, "/api/v1/coverage-plans");
        assertEquals(200, plans.statusCode(), plans.body());
        return firstId(plans.body());
    }

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

    private HttpResponse<String> patchStatus(Session s, String id, String target, long expectedVersion,
                                             String reason) throws Exception {
        String json = reason == null
                ? "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion)
                : "{\"targetStatus\":\"%s\",\"expectedVersion\":%d,\"reason\":\"%s\"}"
                        .formatted(target, expectedVersion, reason);
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/prior-authorizations/" + id + "/status"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
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

    private static String authNumber(String json) {
        Matcher m = AUTH_NUMBER.matcher(json);
        assertTrue(m.find(), "expected an authNumber in: " + json);
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
