package com.healthcloud.relationship;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 3 slice 7 — care-coordinator assignment records against a real server (§14.3, §22). A coordinator/
 * admin assigns a same-tenant coordinator to a patient (ACTIVE); revocation ends it; a duplicate current
 * assignment is a 409; only coordinators/admins may assign; the target must be a CARE_COORDINATOR; tenant +
 * optimistic-lock scoping hold. (The CARE_TEAM consent wiring that consumes these is the next slice.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class CareCoordinatorAssignmentApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern EXPECTED_VERSION = Pattern.compile("\"expectedVersion\":(\\d+)");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String base(String patientId) {
        return "/api/v1/patients/" + patientId + "/coordinator-assignments";
    }

    @Test
    void assigning_a_coordinator_makes_it_active_and_listed() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String coordinatorId = meId(coordinator);
        String patientId = newPatientId(coordinator);

        HttpResponse<String> created = post(coordinator, base(patientId),
                "{\"coordinatorUserId\":\"%s\"}".formatted(coordinatorId));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"status\":\"ACTIVE\""));
        assertTrue(created.body().contains(coordinatorId));

        assertTrue(get(coordinator.session, base(patientId)).body().contains(coordinatorId),
                "the assignment appears in the current list");
    }

    @Test
    void revoking_removes_it_from_the_current_list() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String coordinatorId = meId(coordinator);
        String patientId = newPatientId(coordinator);

        HttpResponse<String> created = post(coordinator, base(patientId),
                "{\"coordinatorUserId\":\"%s\"}".formatted(coordinatorId));
        String id = firstId(created.body());
        long expected = expectedVersion(created.body());

        HttpResponse<String> revoked = post(coordinator, base(patientId) + "/" + id + "/revoke",
                "{\"expectedVersion\":%d}".formatted(expected));
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertTrue(revoked.body().contains("\"status\":\"REVOKED\""));

        assertFalse(get(coordinator.session, base(patientId)).body().contains(coordinatorId),
                "a revoked assignment is no longer current");
    }

    @Test
    void assigning_the_same_coordinator_twice_is_a_conflict() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String coordinatorId = meId(coordinator);
        String patientId = newPatientId(coordinator);
        String body = "{\"coordinatorUserId\":\"%s\"}".formatted(coordinatorId);

        assertEquals(201, post(coordinator, base(patientId), body).statusCode());
        HttpResponse<String> dup = post(coordinator, base(patientId), body);
        assertEquals(409, dup.statusCode());
        assertTrue(dup.body().contains("CONFLICT"));
    }

    @Test
    void only_coordinators_and_admins_may_assign() throws Exception {
        String coordinatorId = meId(loginWithCsrf("coordinator@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> denied = post(provider, base(patientId),
                "{\"coordinatorUserId\":\"%s\"}".formatted(coordinatorId));
        assertEquals(403, denied.statusCode());
        assertTrue(denied.body().contains("ACCESS_DENIED"));
    }

    @Test
    void the_target_must_be_a_coordinator() throws Exception {
        String providerId = meId(loginWithCsrf("provider@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        // A same-tenant user who is not a CARE_COORDINATOR → 400 (not a leaky 404/403).
        HttpResponse<String> bad = post(coordinator, base(patientId),
                "{\"coordinatorUserId\":\"%s\"}".formatted(providerId));
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void a_stale_version_on_revoke_is_a_conflict() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String coordinatorId = meId(coordinator);
        String patientId = newPatientId(coordinator);
        String id = firstId(post(coordinator, base(patientId),
                "{\"coordinatorUserId\":\"%s\"}".formatted(coordinatorId)).body());

        HttpResponse<String> stale = post(coordinator, base(patientId) + "/" + id + "/revoke",
                "{\"expectedVersion\":999}");
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("CONFLICT") && !stale.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void cannot_assign_against_another_tenants_patient() throws Exception {
        Session green = loginWithCsrf("coordinator@greenvalley.example.org");
        String greenPatientId = newPatientId(green);
        String northCoordinatorId = meId(loginWithCsrf("coordinator@northcare.example.org"));

        Session north = loginWithCsrf("coordinator@northcare.example.org");
        HttpResponse<String> cross = post(north, base(greenPatientId),
                "{\"coordinatorUserId\":\"%s\"}".formatted(northCoordinatorId));
        assertEquals(404, cross.statusCode(), "another tenant's patient must be a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String newPatientId(Session s) throws Exception {
        String mrn = "CC-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"%s","fullName":"Coordinator Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(mrn));
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

    private String meId(Session s) throws Exception {
        Matcher m = USER_ID.matcher(get(s.session, "/api/v1/me").body());
        assertTrue(m.find());
        return m.group(1);
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

    private static long expectedVersion(String json) {
        Matcher m = EXPECTED_VERSION.matcher(json);
        assertTrue(m.find(), "expected an expectedVersion in: " + json);
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
