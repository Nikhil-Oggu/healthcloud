package com.healthcloud.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Phase 2 slice 5 — the service-request state machine (§14.6) against a real server: the full legal
 * lifecycle, invalid-move rejection, optimistic locking, per-transition role authorization, mandatory
 * reasons, terminal states, and cross-tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ServiceRequestStateMachineApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern FIRST_USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void full_lifecycle_draft_to_closed_records_history_and_then_is_terminal() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");
        String id = createDraft(s);

        long v = 0;
        v = advance(s, id, "SUBMITTED", v, null);
        v = advance(s, id, "TRIAGED", v, null);
        v = assign(s, id, v); // ASSIGNED is reached by assigning a user (Option A), not a bare status change
        v = advance(s, id, "UNDER_REVIEW", v, null);
        v = advance(s, id, "APPROVED", v, null);
        HttpResponse<String> closed = patchStatus(s, id, "CLOSED", v, null);
        assertEquals(200, closed.statusCode());
        assertTrue(closed.body().contains("\"status\":\"CLOSED\""));
        long closedVersion = version(closed.body());

        // History = creation row + 6 transitions = 7 rows, including the key milestones.
        String history = get(s.session, "/api/v1/requests/" + id + "/history").body();
        assertEquals(7, countOccurrences(history, "\"toStatus\""), "one row per state incl. creation");
        assertTrue(history.contains("APPROVED") && history.contains("CLOSED"));

        // CLOSED is terminal — any further move is rejected.
        HttpResponse<String> afterClose = patchStatus(s, id, "SUBMITTED", closedVersion, null);
        assertEquals(409, afterClose.statusCode());
        assertTrue(afterClose.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void an_illegal_move_is_rejected_as_invalid_transition() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");
        String id = createDraft(s);

        HttpResponse<String> jump = patchStatus(s, id, "APPROVED", 0, null); // DRAFT -> APPROVED not allowed
        assertEquals(409, jump.statusCode());
        assertTrue(jump.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_stale_version_is_a_conflict() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");
        String id = createDraft(s);

        assertEquals(200, patchStatus(s, id, "SUBMITTED", 0, null).statusCode()); // now version 1
        HttpResponse<String> stale = patchStatus(s, id, "TRIAGED", 0, null);       // reused stale version 0
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("CONFLICT") && !stale.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void cancellation_authority_follows_the_rules() throws Exception {
        // Provider has no cancellation authority.
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String id1 = createDraft(coordinator);
        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> providerCancel = patchStatus(provider, id1, "CANCELLED", 0, "nope");
        assertEquals(403, providerCancel.statusCode());

        // A patient may cancel a DRAFT.
        Session patient = loginWithCsrf("patient@northcare.example.org");
        String id2 = createDraft(coordinator);
        HttpResponse<String> patientCancel = patchStatus(patient, id2, "CANCELLED", 0, "changed my mind");
        assertEquals(200, patientCancel.statusCode());
        assertTrue(patientCancel.body().contains("\"status\":\"CANCELLED\""));
    }

    @Test
    void provider_cannot_approve() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String id = createDraft(coordinator);
        long v = 0;
        v = advance(coordinator, id, "SUBMITTED", v, null);
        v = advance(coordinator, id, "TRIAGED", v, null);
        v = assign(coordinator, id, v);
        v = advance(coordinator, id, "UNDER_REVIEW", v, null);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> approve = patchStatus(provider, id, "APPROVED", v, null);
        assertEquals(403, approve.statusCode());
        assertTrue(approve.body().contains("ACCESS_DENIED"));
    }

    @Test
    void rejecting_requires_a_reason() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");
        String id = createDraft(s);
        long v = 0;
        v = advance(s, id, "SUBMITTED", v, null);
        v = advance(s, id, "TRIAGED", v, null);
        v = assign(s, id, v);
        v = advance(s, id, "UNDER_REVIEW", v, null);

        HttpResponse<String> noReason = patchStatus(s, id, "REJECTED", v, null);
        assertEquals(400, noReason.statusCode());
        assertTrue(noReason.body().contains("VALIDATION_FAILED"));

        HttpResponse<String> withReason = patchStatus(s, id, "REJECTED", v, "Not medically necessary");
        assertEquals(200, withReason.statusCode());
        assertTrue(withReason.body().contains("\"status\":\"REJECTED\""));
    }

    @Test
    void cannot_transition_another_tenants_request() throws Exception {
        Session green = loginWithCsrf("admin@greenvalley.example.org");
        String greenId = createDraft(green);

        Session north = loginWithCsrf("coordinator@northcare.example.org");
        HttpResponse<String> cross = patchStatus(north, greenId, "SUBMITTED", 0, null);
        assertEquals(404, cross.statusCode(), "another tenant's request must be a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    /** Create a DRAFT for the session's first patient; returns the request id. */
    private String createDraft(Session s) throws Exception {
        String patientId = firstId(get(s.session, "/api/v1/patients").body());
        HttpResponse<String> created = post(s, "/api/v1/requests", """
                {"patientId":"%s","type":"CLAIM_SUPPORT","title":"Lifecycle"}""".formatted(patientId));
        assertEquals(201, created.statusCode(), "create should succeed: " + created.body());
        return firstId(created.body());
    }

    /** Assign the request to its first eligible assignee (advances TRIAGED → ASSIGNED); returns the new version. */
    private long assign(Session s, String id, long expectedVersion) throws Exception {
        HttpResponse<String> candidates = get(s.session, "/api/v1/requests/" + id + "/assignable-users");
        assertEquals(200, candidates.statusCode(), "assignable-users should succeed: " + candidates.body());
        Matcher m = FIRST_USER_ID.matcher(candidates.body());
        assertTrue(m.find(), "expected at least one assignable user in: " + candidates.body());
        String assigneeUserId = m.group(1);

        HttpResponse<String> assigned = put(s, "/api/v1/requests/" + id + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":%d}".formatted(assigneeUserId, expectedVersion));
        assertEquals(200, assigned.statusCode(), "assign should succeed: " + assigned.body());

        HttpResponse<String> reread = get(s.session, "/api/v1/requests/" + id);
        assertTrue(reread.body().contains("\"status\":\"ASSIGNED\""), "request should now be ASSIGNED");
        return version(reread.body());
    }

    /** Apply a transition expected to succeed; returns the new version. */
    private long advance(Session s, String id, String target, long expectedVersion, String reason) throws Exception {
        HttpResponse<String> r = patchStatus(s, id, target, expectedVersion, reason);
        assertEquals(200, r.statusCode(), "transition to " + target + " should succeed: " + r.body());
        assertTrue(r.body().contains("\"status\":\"" + target + "\""));
        return version(r.body());
    }

    private HttpResponse<String> patchStatus(Session s, String id, String target, long expectedVersion,
                                             String reason) throws Exception {
        String json = reason == null
                ? "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion)
                : "{\"targetStatus\":\"%s\",\"expectedVersion\":%d,\"reason\":\"%s\"}"
                        .formatted(target, expectedVersion, reason);
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/requests/" + id + "/status"))
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

    private HttpResponse<String> put(Session s, String path, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
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
