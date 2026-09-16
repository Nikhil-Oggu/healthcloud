package com.healthcloud.appeal;

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
 * Appeal API (Phase 6 slice 8) against a real embedded server with the seeded demo. Proves the resource is
 * authenticated and tenant-scoped, that an appeal is submitted against an appealable claim with a backend-allocated
 * number, that a non-appealable claim (→ 400) and a duplicate open appeal (→ 409) are rejected, that the
 * resolution lifecycle is enforced (a reviewer upholds/overturns with the always-required reason; the submitter
 * withdraws; a non-decider cannot decide; illegal moves and stale versions are 409), and that the
 * object/relationship gate applies (a provider sees only assigned patients' appeals; another tenant is a secure
 * 404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AppealApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern APPEAL_NUMBER = Pattern.compile("\"appealNumber\":\"([^\"]+)\"");

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

    private String appealJson(String claimId) {
        return """
                {"claimId":"%s","reason":"The claim was decided in error; documentation attached."}"""
                .formatted(claimId);
    }

    /** Drive a fresh claim to REJECTED (an appealable state): create (DRAFT) → submit → reject (reviewer). */
    private String appealableClaimId(Session coordinator, Session reviewer, String patientId) throws Exception {
        String claimId = firstId(post(coordinator, "/api/v1/claims", claimJson(patientId)).body());
        assertEquals(200, patchClaimStatus(coordinator, claimId, "SUBMITTED", 0, null).statusCode());
        assertEquals(200, patchClaimStatus(reviewer, claimId, "REJECTED", 1, "Missing documentation").statusCode());
        return claimId;
    }

    @Test
    void appeals_require_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/appeals").statusCode());
    }

    @Test
    void submit_creates_a_submitted_appeal() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);

        HttpResponse<String> created = post(coordinator, "/api/v1/appeals", appealJson(claimId));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"status\":\"SUBMITTED\""), "a new appeal is SUBMITTED");
        assertTrue(created.body().contains("\"appealNumber\":\"APL-"), "an appeal number is allocated");
        assertTrue(created.body().contains("\"claimId\":\"" + claimId + "\""), "the disputed claim is linked");
    }

    @Test
    void a_reviewer_upholds_a_submitted_appeal() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);
        String appealId = firstId(post(coordinator, "/api/v1/appeals", appealJson(claimId)).body());

        HttpResponse<String> upheld = patchStatus(reviewer, appealId, "UPHELD", 0, "Original decision correct");
        assertEquals(200, upheld.statusCode(), upheld.body());
        assertTrue(upheld.body().contains("\"status\":\"UPHELD\""), "the appeal is upheld");
        assertTrue(upheld.body().contains("\"decidedBy\":\""), "the reviewer is stamped on the decision");
    }

    @Test
    void deciding_requires_a_reason() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);
        String appealId = firstId(post(coordinator, "/api/v1/appeals", appealJson(claimId)).body());

        // No reason → 400 (a reason is mandatory on every appeal transition).
        assertEquals(400, patchStatus(reviewer, appealId, "OVERTURNED", 0, null).statusCode());
        // With a reason → 200 OVERTURNED.
        HttpResponse<String> overturned = patchStatus(reviewer, appealId, "OVERTURNED", 0, "New evidence");
        assertEquals(200, overturned.statusCode(), overturned.body());
        assertTrue(overturned.body().contains("\"status\":\"OVERTURNED\""));
    }

    @Test
    void a_non_decider_cannot_decide() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);
        String appealId = firstId(post(coordinator, "/api/v1/appeals", appealJson(claimId)).body());

        // The coordinator (submitter) can reach the appeal but appeal decisions are the reviewer's (403).
        assertEquals(403, patchStatus(coordinator, appealId, "UPHELD", 0, "Trying to decide").statusCode());
    }

    @Test
    void the_submitter_can_withdraw() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);
        String appealId = firstId(post(coordinator, "/api/v1/appeals", appealJson(claimId)).body());

        HttpResponse<String> withdrawn = patchStatus(coordinator, appealId, "WITHDRAWN", 0, "Filed in error");
        assertEquals(200, withdrawn.statusCode(), withdrawn.body());
        assertTrue(withdrawn.body().contains("\"status\":\"WITHDRAWN\""));
    }

    @Test
    void a_non_appealable_claim_is_a_400() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        // A brand-new claim is DRAFT — not appealable.
        String draftClaimId = firstId(post(coordinator, "/api/v1/claims", claimJson(patientId)).body());

        HttpResponse<String> tooEarly = post(coordinator, "/api/v1/appeals", appealJson(draftClaimId));
        assertEquals(400, tooEarly.statusCode(), tooEarly.body());
        assertTrue(tooEarly.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void a_duplicate_open_appeal_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);

        assertEquals(201, post(coordinator, "/api/v1/appeals", appealJson(claimId)).statusCode());
        // A second open appeal on the same claim is refused.
        HttpResponse<String> dup = post(coordinator, "/api/v1/appeals", appealJson(claimId));
        assertEquals(409, dup.statusCode(), dup.body());
        assertTrue(dup.body().contains("CONFLICT"));
    }

    @Test
    void an_illegal_transition_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);
        String appealId = firstId(post(coordinator, "/api/v1/appeals", appealJson(claimId)).body());

        assertEquals(200, patchStatus(reviewer, appealId, "UPHELD", 0, "Correct").statusCode());
        // UPHELD is terminal — deciding again is an illegal move (409), not a 404/500.
        HttpResponse<String> again = patchStatus(reviewer, appealId, "OVERTURNED", 1, "Changed mind");
        assertEquals(409, again.statusCode(), again.body());
        assertTrue(again.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_stale_version_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);
        String appealId = firstId(post(coordinator, "/api/v1/appeals", appealJson(claimId)).body());

        // Wrong expectedVersion (5, not 0) → optimistic-lock conflict.
        HttpResponse<String> stale = patchStatus(reviewer, appealId, "UPHELD", 5, "Correct");
        assertEquals(409, stale.statusCode(), stale.body());
        assertTrue(stale.body().contains("CONFLICT"));
    }

    @Test
    void a_provider_sees_only_appeals_for_assigned_patients() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = appealableClaimId(coordinator, reviewer, patientId);
        String createdBody = post(coordinator, "/api/v1/appeals", appealJson(claimId)).body();
        String appealNumber = appealNumber(createdBody);
        String appealId = firstId(createdBody);

        String provider = loginWithCsrf("provider@northcare.example.org").session;
        HttpResponse<String> list = get(provider, "/api/v1/appeals");
        assertFalse(list.body().contains(appealNumber),
                "an unassigned patient's appeal is not in the provider's queue");
        // A direct read of it is a secure 404 (no existence leak).
        assertEquals(404, get(provider, "/api/v1/appeals/" + appealId).statusCode());
    }

    @Test
    void fetching_another_tenants_appeal_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(north).body());
        String claimId = appealableClaimId(north, reviewer, patientId);
        String appealId = firstId(post(north, "/api/v1/appeals", appealJson(claimId)).body());

        String green = loginWithCsrf("admin@greenvalley.example.org").session;
        HttpResponse<String> cross = get(green, "/api/v1/appeals/" + appealId);
        assertEquals(404, cross.statusCode(), "another tenant's appeal must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"APL-%s","fullName":"Appeal Test Patient","dateOfBirth":"1990-01-01"}"""
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

    private HttpResponse<String> patchStatus(Session s, String id, String target, long expectedVersion,
                                             String reason) throws Exception {
        return patch(s, "/api/v1/appeals/" + id + "/status", target, expectedVersion, reason);
    }

    private HttpResponse<String> patchClaimStatus(Session s, String id, String target, long expectedVersion,
                                                  String reason) throws Exception {
        return patch(s, "/api/v1/claims/" + id + "/status", target, expectedVersion, reason);
    }

    private HttpResponse<String> patch(Session s, String path, String target, long expectedVersion,
                                       String reason) throws Exception {
        String json = reason == null
                ? "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion)
                : "{\"targetStatus\":\"%s\",\"expectedVersion\":%d,\"reason\":\"%s\"}"
                        .formatted(target, expectedVersion, reason);
        return http.send(
                HttpRequest.newBuilder(uri(path))
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

    private static String appealNumber(String json) {
        Matcher m = APPEAL_NUMBER.matcher(json);
        assertTrue(m.find(), "expected an appealNumber in: " + json);
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
