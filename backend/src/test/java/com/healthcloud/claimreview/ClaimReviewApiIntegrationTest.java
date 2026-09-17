package com.healthcloud.claimreview;

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
 * Claim manual-review API (Phase 6 slice 13) against a real embedded server with the seeded demo. Proves the
 * resource is authenticated and tenant-scoped, that a review is opened against a reachable claim with a
 * backend-allocated number, that a duplicate open review (→ 409) is rejected, that the resolution lifecycle is
 * enforced (a reviewer resolves with the always-required reason; an opener cancels; a non-resolver cannot
 * resolve; illegal moves and stale versions are 409), and that the object/relationship gate applies (a provider
 * sees only assigned patients' reviews; another tenant is a secure 404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ClaimReviewApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern REVIEW_NUMBER = Pattern.compile("\"reviewNumber\":\"([^\"]+)\"");

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

    private String reviewJson(String claimId) {
        return """
                {"claimId":"%s","reason":"Flagged for a manual look."}""".formatted(claimId);
    }

    private String openReviewFor(Session opener, String patientId) throws Exception {
        String claimId = firstId(post(opener, "/api/v1/claims", claimJson(patientId)).body());
        return firstId(post(opener, "/api/v1/claim-reviews", reviewJson(claimId)).body());
    }

    @Test
    void reviews_require_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/claim-reviews").statusCode());
    }

    @Test
    void open_creates_an_open_review() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(post(coordinator, "/api/v1/claims", claimJson(patientId)).body());

        HttpResponse<String> created = post(coordinator, "/api/v1/claim-reviews", reviewJson(claimId));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"status\":\"OPEN\""), "a new review is OPEN");
        assertTrue(created.body().contains("\"reviewNumber\":\"MRV-"), "a review number is allocated");
        assertTrue(created.body().contains("\"claimId\":\"" + claimId + "\""), "the reviewed claim is linked");
    }

    @Test
    void a_reviewer_resolves_an_open_review() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String reviewId = openReviewFor(coordinator, patientId);

        HttpResponse<String> resolved = patchStatus(reviewer, reviewId, "RESOLVED", 0, "Reviewed; charges are valid");
        assertEquals(200, resolved.statusCode(), resolved.body());
        assertTrue(resolved.body().contains("\"status\":\"RESOLVED\""), "the review is resolved");
        assertTrue(resolved.body().contains("\"resolvedBy\":\""), "the reviewer is stamped on the resolution");
    }

    @Test
    void resolving_requires_a_reason() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String reviewId = openReviewFor(coordinator, patientId);

        // No reason → 400 (a reason is mandatory on every review transition).
        assertEquals(400, patchStatus(reviewer, reviewId, "RESOLVED", 0, null).statusCode());
        // With a reason → 200 RESOLVED.
        HttpResponse<String> resolved = patchStatus(reviewer, reviewId, "RESOLVED", 0, "No issue found");
        assertEquals(200, resolved.statusCode(), resolved.body());
        assertTrue(resolved.body().contains("\"status\":\"RESOLVED\""));
    }

    @Test
    void a_non_resolver_cannot_resolve() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String reviewId = openReviewFor(coordinator, patientId);

        // The coordinator (opener) can reach the review but resolving is the reviewer's disposition (403).
        assertEquals(403, patchStatus(coordinator, reviewId, "RESOLVED", 0, "Trying to resolve").statusCode());
    }

    @Test
    void an_opener_can_cancel() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String reviewId = openReviewFor(coordinator, patientId);

        HttpResponse<String> cancelled = patchStatus(coordinator, reviewId, "CANCELLED", 0, "Opened in error");
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertTrue(cancelled.body().contains("\"status\":\"CANCELLED\""));
    }

    @Test
    void a_duplicate_open_review_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(post(coordinator, "/api/v1/claims", claimJson(patientId)).body());

        assertEquals(201, post(coordinator, "/api/v1/claim-reviews", reviewJson(claimId)).statusCode());
        // A second open review on the same claim is refused.
        HttpResponse<String> dup = post(coordinator, "/api/v1/claim-reviews", reviewJson(claimId));
        assertEquals(409, dup.statusCode(), dup.body());
        assertTrue(dup.body().contains("CONFLICT"));
    }

    @Test
    void an_illegal_transition_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String reviewId = openReviewFor(coordinator, patientId);

        assertEquals(200, patchStatus(reviewer, reviewId, "RESOLVED", 0, "Resolved").statusCode());
        // RESOLVED is terminal — cancelling it is an illegal move (409), not a 404/500.
        HttpResponse<String> again = patchStatus(coordinator, reviewId, "CANCELLED", 1, "Changed mind");
        assertEquals(409, again.statusCode(), again.body());
        assertTrue(again.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_stale_version_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String reviewId = openReviewFor(coordinator, patientId);

        // Wrong expectedVersion (5, not 0) → optimistic-lock conflict.
        HttpResponse<String> stale = patchStatus(reviewer, reviewId, "RESOLVED", 5, "Resolved");
        assertEquals(409, stale.statusCode(), stale.body());
        assertTrue(stale.body().contains("CONFLICT"));
    }

    @Test
    void a_provider_sees_only_reviews_for_assigned_patients() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(post(coordinator, "/api/v1/claims", claimJson(patientId)).body());
        String createdBody = post(coordinator, "/api/v1/claim-reviews", reviewJson(claimId)).body();
        String reviewNumber = reviewNumber(createdBody);
        String reviewId = firstId(createdBody);

        String provider = loginWithCsrf("provider@northcare.example.org").session;
        HttpResponse<String> list = get(provider, "/api/v1/claim-reviews");
        assertFalse(list.body().contains(reviewNumber),
                "an unassigned patient's review is not in the provider's queue");
        // A direct read of it is a secure 404 (no existence leak).
        assertEquals(404, get(provider, "/api/v1/claim-reviews/" + reviewId).statusCode());
    }

    @Test
    void fetching_another_tenants_review_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(north).body());
        String reviewId = openReviewFor(north, patientId);

        String green = loginWithCsrf("admin@greenvalley.example.org").session;
        HttpResponse<String> cross = get(green, "/api/v1/claim-reviews/" + reviewId);
        assertEquals(404, cross.statusCode(), "another tenant's review must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    @Test
    void the_list_returns_a_page_envelope() throws Exception {
        String reviewer = loginWithCsrf("reviewer@northcare.example.org").session;
        HttpResponse<String> list = get(reviewer, "/api/v1/claim-reviews?size=5");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"content\":["), "the response is a page envelope");
        assertTrue(list.body().contains("\"page\":0"));
        assertTrue(list.body().contains("\"totalElements\":"));
    }

    @Test
    void an_unknown_sort_field_is_a_400() throws Exception {
        String reviewer = loginWithCsrf("reviewer@northcare.example.org").session;
        HttpResponse<String> bad = get(reviewer, "/api/v1/claim-reviews?sort=ssn");
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("VALIDATION_FAILED"), "sorting by a non-allowlisted field is a clean 400");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"MRV-%s","fullName":"Review Test Patient","dateOfBirth":"1990-01-01"}"""
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
        String json = reason == null
                ? "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion)
                : "{\"targetStatus\":\"%s\",\"expectedVersion\":%d,\"reason\":\"%s\"}"
                        .formatted(target, expectedVersion, reason);
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/claim-reviews/" + id + "/status"))
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

    private static String reviewNumber(String json) {
        Matcher m = REVIEW_NUMBER.matcher(json);
        assertTrue(m.find(), "expected a reviewNumber in: " + json);
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
