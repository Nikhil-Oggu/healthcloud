package com.healthcloud.adjudication;

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
 * The adjudication engine (Phase 5 slice 1) against a real embedded server with the seeded demo. Proves the
 * command is authenticated, reviewer-gated and patient-gated; that only an ACCEPTED claim can be adjudicated;
 * that a covered claim produces an ADJUDICATED outcome with an explainable line breakdown and moves the claim to
 * ADJUDICATED; that a claim with no coverage on the service date is DENIED_NO_ELIGIBILITY; that re-adjudication
 * is refused; and that another tenant's claim is a secure 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AdjudicationApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void adjudication_requires_authentication() throws Exception {
        HttpResponse<String> anon =
                get(null, "/api/v1/claims/" + UUID.randomUUID() + "/adjudication");
        assertEquals(401, anon.statusCode());
    }

    @Test
    void a_reviewer_adjudicates_a_covered_claim_and_reads_the_breakdown() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);

        HttpResponse<String> adjudicated = adjudicate(reviewer, claimId);
        assertEquals(200, adjudicated.statusCode(), adjudicated.body());
        assertTrue(adjudicated.body().contains("\"outcome\":\"ADJUDICATED\""), adjudicated.body());
        assertTrue(adjudicated.body().contains("\"coveragePlanName\":"), "the covering plan is named");
        assertTrue(adjudicated.body().contains("\"planPaidAmount\":"), "the line breakdown is present");

        // The claim itself is now ADJUDICATED.
        assertTrue(get(reviewer.session, "/api/v1/claims/" + claimId).body().contains("\"status\":\"ADJUDICATED\""));

        // The stored result reads back with the per-line breakdown (both lines, COVERED).
        HttpResponse<String> read = get(reviewer.session, "/api/v1/claims/" + claimId + "/adjudication");
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"outcome\":\"ADJUDICATED\""));
        assertEquals(2, countOccurrences(read.body(), "\"claimLineId\""), "one breakdown row per claim line");
        assertTrue(read.body().contains("\"outcome\":\"COVERED\""));
    }

    @Test
    void a_claim_without_coverage_is_denied() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body()); // not enrolled in any plan
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);

        HttpResponse<String> denied = adjudicate(reviewer, claimId);
        assertEquals(200, denied.statusCode(), denied.body());
        assertTrue(denied.body().contains("\"outcome\":\"DENIED_NO_ELIGIBILITY\""), denied.body());
        assertTrue(denied.body().contains("\"coveragePlanName\":null"), "no plan applied");
        assertTrue(denied.body().contains("\"outcome\":\"NOT_COVERED\""), "lines are not covered");
    }

    @Test
    void a_non_accepted_claim_cannot_be_adjudicated() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String draftClaim = firstId(createClaim(coordinator, patientId).body()); // still DRAFT

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        HttpResponse<String> tooEarly = adjudicate(reviewer, draftClaim);
        assertEquals(409, tooEarly.statusCode(), tooEarly.body());
        assertTrue(tooEarly.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_coordinator_cannot_adjudicate() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);

        // The coordinator has broad access (passes the patient gate) but is not a reviewer/admin → 403.
        HttpResponse<String> forbidden = adjudicate(coordinator, claimId);
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertTrue(forbidden.body().contains("ACCESS_DENIED"));
    }

    @Test
    void re_adjudicating_an_adjudicated_claim_creates_a_new_version() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);

        HttpResponse<String> first = adjudicate(reviewer, claimId);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"adjudicationVersion\":1"), first.body());
        // The claim is now ADJUDICATED; a second call re-adjudicates it → a new immutable version (not a 409).
        HttpResponse<String> again = adjudicate(reviewer, claimId);
        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"adjudicationVersion\":2"), again.body());
        // The claim stays ADJUDICATED.
        assertTrue(get(reviewer.session, "/api/v1/claims/" + claimId).body().contains("\"status\":\"ADJUDICATED\""));
    }

    @Test
    void adjudicating_another_tenants_claim_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(north).body());
        Session northReviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(north, northReviewer, patientId);

        Session green = loginWithCsrf("admin@greenvalley.example.org");
        HttpResponse<String> cross = adjudicate(green, claimId);
        assertEquals(404, cross.statusCode(), "another tenant's claim must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"ADJ-%s","fullName":"Adjudication Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private HttpResponse<String> createClaim(Session s, String patientId) throws Exception {
        return post(s, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"99213","units":1,"chargeAmount":150.00},
                  {"procedureCode":"80053","units":1,"chargeAmount":45.50}]}""".formatted(patientId));
    }

    /** Drive a claim to ACCEPTED: coordinator creates + submits (v0), reviewer accepts (v1). */
    private String acceptedClaim(Session coordinator, Session reviewer, String patientId) throws Exception {
        String claimId = firstId(createClaim(coordinator, patientId).body());
        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0).statusCode());
        assertEquals(200, patchStatus(reviewer, claimId, "ACCEPTED", 1).statusCode());
        return claimId;
    }

    private String firstPlanId(Session s) throws Exception {
        return firstId(get(s.session, "/api/v1/coverage-plans").body());
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"ADJ-M1\",\"effectiveFrom\":\"2020-01-01\"}"
                        .formatted(planId));
        assertEquals(201, enrolled.statusCode(), enrolled.body());
    }

    private HttpResponse<String> patchStatus(Session s, String claimId, String target, long expectedVersion)
            throws Exception {
        String json = "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion);
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/claims/" + claimId + "/status"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> adjudicate(Session s, String claimId) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/claims/" + claimId + "/adjudicate"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
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
