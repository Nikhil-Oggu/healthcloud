package com.healthcloud.claim;

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
 * Phase 4 slice 4 — the claim submission/validation state machine against a real server: the legal lifecycle
 * (DRAFT→SUBMITTED→ACCEPTED/REJECTED), history recording, invalid-move rejection, per-transition role
 * authorization (submitter submits/cancels; reviewer accepts/rejects), mandatory reasons, submission
 * validation, the reserved ADJUDICATED status, optimistic locking, and cross-tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ClaimStateMachineApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void submit_then_accept_records_the_history() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(createClaim(coordinator, patientId).body());

        HttpResponse<String> submitted = patchStatus(coordinator, claimId, "SUBMITTED", 0, null);
        assertEquals(200, submitted.statusCode(), submitted.body());
        assertTrue(submitted.body().contains("\"status\":\"SUBMITTED\""));

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        // The reviewer accepts the submitted claim (their write action).
        HttpResponse<String> accepted = patchStatus(reviewer, claimId, "ACCEPTED", version(submitted.body()), null);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertTrue(accepted.body().contains("\"status\":\"ACCEPTED\""));

        // History = creation row + submit + accept = 3 rows.
        String history = get(reviewer.session, "/api/v1/claims/" + claimId + "/history").body();
        assertEquals(3, countOccurrences(history, "\"toStatus\""), "one row per state incl. creation");
        assertTrue(history.contains("\"toStatus\":\"DRAFT\"") && history.contains("\"toStatus\":\"ACCEPTED\""));
    }

    @Test
    void rejecting_requires_a_reason() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(createClaim(coordinator, patientId).body());
        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0, null).statusCode());

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        HttpResponse<String> noReason = patchStatus(reviewer, claimId, "REJECTED", 1, null);
        assertEquals(400, noReason.statusCode());
        assertTrue(noReason.body().contains("VALIDATION_FAILED"));

        HttpResponse<String> withReason = patchStatus(reviewer, claimId, "REJECTED", 1, "Coding does not match");
        assertEquals(200, withReason.statusCode());
        assertTrue(withReason.body().contains("\"status\":\"REJECTED\""));
    }

    @Test
    void an_illegal_move_is_invalid_transition() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(createClaim(coordinator, patientId).body());

        HttpResponse<String> jump = patchStatus(coordinator, claimId, "ACCEPTED", 0, null); // DRAFT→ACCEPTED
        assertEquals(409, jump.statusCode());
        assertTrue(jump.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_reviewer_cannot_submit_and_a_provider_cannot_accept() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");

        // Reviewer cannot submit (broad access passes the gate, the role check fails → 403).
        String freshPatient = firstId(createPatient(coordinator).body());
        String claimId = firstId(createClaim(coordinator, freshPatient).body());
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        HttpResponse<String> reviewerSubmit = patchStatus(reviewer, claimId, "SUBMITTED", 0, null);
        assertEquals(403, reviewerSubmit.statusCode());
        assertTrue(reviewerSubmit.body().contains("ACCESS_DENIED"));

        // Provider cannot accept. Use Sam Sample (the provider is seeded-assigned) so the gate passes and the
        // role check is what rejects them (403, not a 404 from the relationship gate).
        String samId = seededPatientId(coordinator, "Sam Sample");
        String samClaim = firstId(createClaim(coordinator, samId).body());
        assertEquals(200, patchStatus(coordinator, samClaim, "SUBMITTED", 0, null).statusCode());
        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> providerAccept = patchStatus(provider, samClaim, "ACCEPTED", 1, null);
        assertEquals(403, providerAccept.statusCode());
        assertTrue(providerAccept.body().contains("ACCESS_DENIED"));
    }

    @Test
    void a_stale_version_is_a_conflict() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(createClaim(coordinator, patientId).body());

        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0, null).statusCode()); // now version 1
        HttpResponse<String> stale = patchStatus(coordinator, claimId, "CANCELLED", 0, "reuse stale version");
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("CONFLICT") && !stale.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void adjudicated_is_reserved_for_the_engine() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(createClaim(coordinator, patientId).body());
        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0, null).statusCode());
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        assertEquals(200, patchStatus(reviewer, claimId, "ACCEPTED", 1, null).statusCode());

        // ACCEPTED→ADJUDICATED is structurally legal, but a bare status change to ADJUDICATED is refused.
        HttpResponse<String> adjudicate = patchStatus(reviewer, claimId, "ADJUDICATED", 2, null);
        assertEquals(409, adjudicate.statusCode());
        assertTrue(adjudicate.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_zero_total_claim_cannot_be_submitted() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        // A single line with a zero charge → total 0 → not submittable.
        String claimId = firstId(post(coordinator, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"99213","chargeAmount":0.00}]}""".formatted(patientId)).body());

        HttpResponse<String> submit = patchStatus(coordinator, claimId, "SUBMITTED", 0, null);
        assertEquals(400, submit.statusCode(), submit.body());
        assertTrue(submit.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void cannot_transition_another_tenants_claim() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(north).body());
        String claimId = firstId(createClaim(north, patientId).body());

        Session green = loginWithCsrf("admin@greenvalley.example.org");
        HttpResponse<String> cross = patchStatus(green, claimId, "SUBMITTED", 0, null);
        assertEquals(404, cross.statusCode(), "another tenant's claim must be a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"SM-%s","fullName":"State Machine Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private HttpResponse<String> createClaim(Session s, String patientId) throws Exception {
        return post(s, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"99213","units":1,"chargeAmount":150.00},
                  {"procedureCode":"80053","units":1,"chargeAmount":45.50}]}""".formatted(patientId));
    }

    private String seededPatientId(Session s, String fullName) throws Exception {
        String body = get(s.session, "/api/v1/patients").body();
        Matcher m = Pattern.compile(
                "\\{\"id\":\"([0-9a-fA-F-]{36})\"[^}]*\"fullName\":\"" + Pattern.quote(fullName) + "\"")
                .matcher(body);
        assertTrue(m.find(), "expected a patient named " + fullName + " in: " + body);
        return m.group(1);
    }

    private HttpResponse<String> patchStatus(Session s, String id, String target, long expectedVersion,
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
