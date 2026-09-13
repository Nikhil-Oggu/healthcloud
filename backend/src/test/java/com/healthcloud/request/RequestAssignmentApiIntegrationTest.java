package com.healthcloud.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 2 slice 8 — request assignment against a real server (§14.5, Option A). Assigning a TRIAGED
 * request advances it to ASSIGNED and records the assignee; reassignment supersedes the prior row;
 * only coordinators/admins may assign; the assignee must be a same-tenant provider/reviewer; the
 * ASSIGNED status can't be reached by a bare status change; tenant + optimistic-lock scoping hold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class RequestAssignmentApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");

    @Value("${local.server.port}")
    int port;

    @Autowired RequestAssignmentRepository assignmentRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Create a request and advance it to TRIAGED; returns {id, version}. */
    private String[] triagedRequest(Session s) throws Exception {
        String patientId = firstId(get(s.session, "/api/v1/patients").body());
        HttpResponse<String> created = post(s, "/api/v1/requests", """
                {"patientId":"%s","type":"CLAIM_SUPPORT","title":"Assign me"}""".formatted(patientId));
        assertEquals(201, created.statusCode());
        String id = firstId(created.body());
        long v = version(patchStatus(s, id, "SUBMITTED", 0).body());
        v = version(patchStatus(s, id, "TRIAGED", v).body());
        return new String[] {id, Long.toString(v)};
    }

    @Test
    void assigning_a_triaged_request_advances_it_to_assigned_and_records_the_assignee() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String[] req = triagedRequest(coordinator);
        String id = req[0];
        long v = Long.parseLong(req[1]);

        List<String> assignees = userIds(get(coordinator.session, "/api/v1/requests/" + id + "/assignable-users").body());
        assertTrue(assignees.size() >= 2, "NorthCare seeds a provider and a reviewer as assignable");

        HttpResponse<String> assigned = put(coordinator, "/api/v1/requests/" + id + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":%d}".formatted(assignees.get(0), v));
        assertEquals(200, assigned.statusCode(), assigned.body());

        // The request is now ASSIGNED and the active assignment is readable.
        assertTrue(get(coordinator.session, "/api/v1/requests/" + id).body().contains("\"status\":\"ASSIGNED\""));
        HttpResponse<String> current = get(coordinator.session, "/api/v1/requests/" + id + "/assignment");
        assertEquals(200, current.statusCode());
        assertTrue(current.body().contains(assignees.get(0)), "current assignment should name the assignee");

        // One ACTIVE assignment exists, and a TRIAGED→ASSIGNED history row was written.
        long active = assignmentRepo.findAll().stream()
                .filter(a -> a.getServiceRequestId().equals(UUID.fromString(id)))
                .filter(a -> a.getStatus() == RequestAssignmentStatus.ACTIVE)
                .count();
        assertEquals(1, active);
        assertTrue(get(coordinator.session, "/api/v1/requests/" + id + "/history").body().contains("ASSIGNED"));
    }

    @Test
    void reassignment_supersedes_the_previous_assignment() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String[] req = triagedRequest(coordinator);
        String id = req[0];
        long v = Long.parseLong(req[1]);

        List<String> assignees = userIds(get(coordinator.session, "/api/v1/requests/" + id + "/assignable-users").body());
        // First assignment (advances to ASSIGNED, bumps the request version).
        assertEquals(200, put(coordinator, "/api/v1/requests/" + id + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":%d}".formatted(assignees.get(0), v)).statusCode());
        long v2 = version(get(coordinator.session, "/api/v1/requests/" + id).body());

        // Reassign to a different user with the current version — succeeds and swaps the assignee.
        HttpResponse<String> reassigned = put(coordinator, "/api/v1/requests/" + id + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":%d}".formatted(assignees.get(1), v2));
        assertEquals(200, reassigned.statusCode(), reassigned.body());
        assertTrue(get(coordinator.session, "/api/v1/requests/" + id + "/assignment").body().contains(assignees.get(1)));

        // Exactly one ACTIVE row remains; the earlier one is SUPERSEDED.
        var rows = assignmentRepo.findAll().stream()
                .filter(a -> a.getServiceRequestId().equals(UUID.fromString(id)))
                .toList();
        assertEquals(2, rows.size(), "both assignments retained (history)");
        assertEquals(1, rows.stream().filter(a -> a.getStatus() == RequestAssignmentStatus.ACTIVE).count());
    }

    @Test
    void only_coordinators_and_admins_may_assign() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String[] req = triagedRequest(coordinator);
        String id = req[0];
        long v = Long.parseLong(req[1]);
        String assignee = userIds(get(coordinator.session, "/api/v1/requests/" + id + "/assignable-users").body()).get(0);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> denied = put(provider, "/api/v1/requests/" + id + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":%d}".formatted(assignee, v));
        assertEquals(403, denied.statusCode());
        assertTrue(denied.body().contains("ACCESS_DENIED"));
    }

    @Test
    void the_assignee_must_be_a_provider_or_reviewer() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String[] req = triagedRequest(coordinator);
        String id = req[0];
        long v = Long.parseLong(req[1]);

        // The patient is a same-tenant user but not an assignable role → 400 (not a leaky 404/403).
        String patientUserId = meId(loginWithCsrf("patient@northcare.example.org"));
        HttpResponse<String> bad = put(coordinator, "/api/v1/requests/" + id + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":%d}".formatted(patientUserId, v));
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void cannot_assign_before_triage() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(get(coordinator.session, "/api/v1/patients").body());
        String id = firstId(post(coordinator, "/api/v1/requests", """
                {"patientId":"%s","type":"CLAIM_SUPPORT","title":"Too early"}""".formatted(patientId)).body());
        // Still DRAFT — assignment is only legal from TRIAGED/ASSIGNED.
        String assignee = userIds(get(coordinator.session, "/api/v1/requests/" + id + "/assignable-users").body()).get(0);
        HttpResponse<String> early = put(coordinator, "/api/v1/requests/" + id + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":0}".formatted(assignee));
        assertEquals(409, early.statusCode());
        assertTrue(early.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void assigned_cannot_be_reached_by_a_bare_status_change() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String[] req = triagedRequest(coordinator);
        HttpResponse<String> manual = patchStatus(coordinator, req[0], "ASSIGNED", Long.parseLong(req[1]));
        assertEquals(409, manual.statusCode());
        assertTrue(manual.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_stale_version_is_a_conflict() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String[] req = triagedRequest(coordinator);
        String id = req[0];
        String assignee = userIds(get(coordinator.session, "/api/v1/requests/" + id + "/assignable-users").body()).get(0);
        HttpResponse<String> stale = put(coordinator, "/api/v1/requests/" + id + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":0}".formatted(assignee)); // version is >0 after triage
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("CONFLICT") && !stale.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void cannot_assign_another_tenants_request() throws Exception {
        Session green = loginWithCsrf("coordinator@greenvalley.example.org");
        String[] greenReq = triagedRequest(green);
        String greenAssignee = userIds(
                get(green.session, "/api/v1/requests/" + greenReq[0] + "/assignable-users").body()).get(0);

        Session north = loginWithCsrf("coordinator@northcare.example.org");
        HttpResponse<String> cross = put(north, "/api/v1/requests/" + greenReq[0] + "/assignment",
                "{\"assigneeUserId\":\"%s\",\"expectedVersion\":%s}".formatted(greenAssignee, greenReq[1]));
        assertEquals(404, cross.statusCode(), "another tenant's request must be a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> patchStatus(Session s, String id, String target, long expectedVersion)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/requests/" + id + "/status"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion)))
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

    /** The signed-in user's own id (from /me). */
    private String meId(Session s) throws Exception {
        String body = get(s.session, "/api/v1/me").body();
        Matcher m = USER_ID.matcher(body);
        assertTrue(m.find(), "expected userId in /me: " + body);
        return m.group(1);
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

    private static List<String> userIds(String json) {
        List<String> ids = new ArrayList<>();
        Matcher m = USER_ID.matcher(json);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static long version(String json) {
        Matcher m = VERSION.matcher(json);
        assertTrue(m.find(), "expected a version in: " + json);
        return Long.parseLong(m.group(1));
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
