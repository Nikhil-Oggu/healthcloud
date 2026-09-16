package com.healthcloud.reprocessing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Reprocessing-batch API (Phase 6 slice 15) against a real embedded server with the seeded demo. Proves the
 * resource is authenticated and tenant-scoped; that running a batch for a coverage plan re-adjudicates the plan's
 * ADJUDICATED claims (each gets a new immutable adjudication version) and lands COMPLETED with per-claim items;
 * that the batch is gated to reviewers/admins (a provider → 403); that a plan outside the tenant → 400; that a
 * batch touches only its own plan's claims; and that another tenant's batch is a secure 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ReprocessingApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String claimJson(String patientId) {
        return """
                {"patientId":"%s","serviceDate":"2025-11-01",\
                "lines":[{"procedureCode":"99213","units":1,"chargeAmount":150.00}]}""".formatted(patientId);
    }

    private String planJson(String planCode) {
        return """
                {"planCode":"%s","name":"Reprocess Test PPO","planType":"PPO",\
                "deductibleAmount":0.00,"coinsuranceRate":0.2000,"copayAmount":0.00}""".formatted(planCode);
    }

    private String enrollJson(String planId) {
        return """
                {"coveragePlanId":"%s","memberId":"M-%s","effectiveFrom":"2025-01-01"}"""
                .formatted(planId, UUID.randomUUID().toString().substring(0, 8));
    }

    private String batchJson(String planId) {
        return "{\"coveragePlanId\":\"%s\"}".formatted(planId);
    }

    /** Create a plan in the caller's tenant and return its id. */
    private String createPlan(Session admin) throws Exception {
        String planCode = "RPB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        HttpResponse<String> created = post(admin, "/api/v1/coverage-plans", planJson(planCode));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    /**
     * Drive a fresh, enrolled patient's claim to ADJUDICATED on the given plan: create (DRAFT) → submit → accept
     * → adjudicate. The patient is enrolled on the plan for the service date, so version 1 is a covered
     * adjudication that references the plan (in scope for a batch).
     */
    private String adjudicatedClaimOnPlan(Session admin, String patientId, String planId) throws Exception {
        assertEquals(201, post(admin, "/api/v1/patients/" + patientId + "/eligibility",
                enrollJson(planId)).statusCode());
        String claimId = firstId(post(admin, "/api/v1/claims", claimJson(patientId)).body());
        assertEquals(200, patchClaimStatus(admin, claimId, "SUBMITTED", 0, null).statusCode());
        assertEquals(200, patchClaimStatus(admin, claimId, "ACCEPTED", 1, null).statusCode());
        HttpResponse<String> adjudicated = postNoBody(admin, "/api/v1/claims/" + claimId + "/adjudicate");
        assertEquals(200, adjudicated.statusCode(), adjudicated.body());
        assertTrue(adjudicated.body().contains("\"coveragePlanId\":\"" + planId + "\""),
                "the claim adjudicated on the plan");
        return claimId;
    }

    @Test
    void reprocessing_batches_require_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/reprocessing-batches").statusCode());
    }

    @Test
    void running_a_batch_reajudicates_the_plans_claims() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String patientId = firstId(createPatient(admin).body());
        String planId = createPlan(admin);
        String claimId = adjudicatedClaimOnPlan(admin, patientId, planId);

        // One immutable adjudication version exists after the initial adjudicate.
        assertEquals(1, countVersions(
                get(admin.session, "/api/v1/claims/" + claimId + "/adjudication/versions").body()));

        HttpResponse<String> batch = post(admin, "/api/v1/reprocessing-batches", batchJson(planId));
        assertEquals(201, batch.statusCode(), batch.body());
        assertTrue(batch.body().contains("\"batchNumber\":\"RPB-"), "a batch number is allocated");
        assertTrue(batch.body().contains("\"status\":\"COMPLETED\""), "no failures → COMPLETED");
        assertTrue(batch.body().contains("\"totalCount\":1"), "one claim was in scope");
        assertTrue(batch.body().contains("\"succeededCount\":1"), "it reprocessed cleanly");
        assertTrue(batch.body().contains("\"failedCount\":0"));
        assertTrue(batch.body().contains("\"outcome\":\"SUCCEEDED\""), "the item succeeded");
        assertTrue(batch.body().contains("\"adjudicationVersion\":2"), "a new version 2 was written");

        // The batch re-ran the engine: a second immutable adjudication version now exists.
        assertEquals(2, countVersions(
                get(admin.session, "/api/v1/claims/" + claimId + "/adjudication/versions").body()));
    }

    @Test
    void a_provider_cannot_run_a_batch() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> denied = post(provider, "/api/v1/reprocessing-batches", batchJson(planId));
        assertEquals(403, denied.statusCode(), denied.body());
    }

    @Test
    void a_plan_outside_the_tenant_is_a_400() throws Exception {
        Session green = loginWithCsrf("admin@greenvalley.example.org");
        String greenPlanId = createPlan(green);

        Session north = loginWithCsrf("admin@northcare.example.org");
        HttpResponse<String> crossPlan = post(north, "/api/v1/reprocessing-batches", batchJson(greenPlanId));
        assertEquals(400, crossPlan.statusCode(), crossPlan.body());
        assertTrue(crossPlan.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void a_batch_touches_only_its_own_plans_claims() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String patientId = firstId(createPatient(admin).body());
        String planA = createPlan(admin);
        String claimId = adjudicatedClaimOnPlan(admin, patientId, planA); // claim adjudicated on plan A (v1)
        String planB = createPlan(admin);                                 // a different plan, no claims

        HttpResponse<String> batch = post(admin, "/api/v1/reprocessing-batches", batchJson(planB));
        assertEquals(201, batch.statusCode(), batch.body());
        assertTrue(batch.body().contains("\"totalCount\":0"), "plan B has no claims in scope");
        assertTrue(batch.body().contains("\"status\":\"COMPLETED\""));

        // Plan A's claim was untouched — still on its single version 1.
        assertEquals(1, countVersions(
                get(admin.session, "/api/v1/claims/" + claimId + "/adjudication/versions").body()));
    }

    @Test
    void fetching_another_tenants_batch_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(north);
        String batchId = firstId(post(north, "/api/v1/reprocessing-batches", batchJson(planId)).body());

        String green = loginWithCsrf("admin@greenvalley.example.org").session;
        HttpResponse<String> cross = get(green, "/api/v1/reprocessing-batches/" + batchId);
        assertEquals(404, cross.statusCode(), "another tenant's batch must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"RPB-%s","fullName":"Reprocess Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
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

    private HttpResponse<String> postNoBody(Session s, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchClaimStatus(Session s, String id, String target, long expectedVersion,
                                                  String reason) throws Exception {
        String json = reason == null
                ? "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion)
                : "{\"targetStatus\":\"%s\",\"expectedVersion\":%d,\"reason\":\"%s\"}"
                        .formatted(target, expectedVersion, reason);
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/claims/" + id + "/status"))
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

    /** Count adjudication versions in a {@code /adjudication/versions} array response. */
    private static int countVersions(String json) {
        Matcher m = Pattern.compile("\"adjudicationVersion\":").matcher(json);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
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
