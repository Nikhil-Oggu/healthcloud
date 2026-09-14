package com.healthcloud.patient;

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
 * Phase 3 slice 5 — the object/relationship access gate (§14.3, §21 layer 6, §60). A PROVIDER may read only
 * patients they are actively assigned to: an unassigned patient is a secure 404 (§21.5); revoking the
 * assignment removes access. Coordinators/admins keep broad tenant access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PatientRelationshipGateApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern EXPECTED_VERSION = Pattern.compile("\"expectedVersion\":(\\d+)");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void a_provider_sees_only_assigned_patients() throws Exception {
        String providerId = meId(loginWithCsrf("provider@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String mrnA = "GT-A-" + suffix();
        String mrnB = "GT-B-" + suffix();
        String idA = firstId(createPatient(coordinator, mrnA).body());
        String idB = firstId(createPatient(coordinator, mrnB).body());

        // Assign the provider to A only.
        assertEquals(201, assign(coordinator, idA, providerId).statusCode());

        Session provider = loginWithCsrf("provider@northcare.example.org");
        String list = get(provider.session, "/api/v1/patients").body();
        assertTrue(list.contains(mrnA), "the provider sees the patient they are assigned to");
        assertFalse(list.contains(mrnB), "the provider does not see an unassigned patient");

        assertEquals(200, get(provider.session, "/api/v1/patients/" + idA).statusCode());
        HttpResponse<String> unassigned = get(provider.session, "/api/v1/patients/" + idB);
        assertEquals(404, unassigned.statusCode(), "an unassigned patient is a secure 404, not 403");
        assertTrue(unassigned.body().contains("NOT_FOUND"));
    }

    @Test
    void a_coordinator_sees_all_patients_regardless_of_assignment() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String mrn = "GT-C-" + suffix();
        String id = firstId(createPatient(coordinator, mrn).body());

        // No provider assignment at all — the coordinator still sees it (broad coordination access).
        assertEquals(200, get(coordinator.session, "/api/v1/patients/" + id).statusCode());
        assertTrue(get(coordinator.session, "/api/v1/patients").body().contains(mrn));
    }

    @Test
    void revoking_the_assignment_removes_provider_access() throws Exception {
        String providerId = meId(loginWithCsrf("provider@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String id = firstId(createPatient(coordinator, "GT-R-" + suffix()).body());

        HttpResponse<String> assigned = assign(coordinator, id, providerId);
        String assignmentId = firstId(assigned.body());
        long expected = expectedVersion(assigned.body());

        Session provider = loginWithCsrf("provider@northcare.example.org");
        assertEquals(200, get(provider.session, "/api/v1/patients/" + id).statusCode());

        // Revoke → the provider loses access (secure 404).
        assertEquals(200, post(coordinator,
                "/api/v1/patients/" + id + "/provider-assignments/" + assignmentId + "/revoke",
                "{\"expectedVersion\":%d}".formatted(expected)).statusCode());
        assertEquals(404, get(provider.session, "/api/v1/patients/" + id).statusCode(),
                "after revocation the provider can no longer read the patient");
    }

    @Test
    void seeded_providers_have_baseline_assignments() throws Exception {
        // The seeder assigns each org's provider to 2 of its 3 patients — so a seeded provider is not empty.
        Session provider = loginWithCsrf("provider@northcare.example.org");
        String list = get(provider.session, "/api/v1/patients").body();
        assertTrue(list.contains("NC-0001") && list.contains("NC-0002"),
                "the seeded provider sees their two assigned patients");
        assertFalse(list.contains("NC-0003"), "the seeded provider is not assigned to the third patient");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String suffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private HttpResponse<String> createPatient(Session s, String mrn) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"%s","fullName":"Gate Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(mrn));
    }

    private HttpResponse<String> assign(Session s, String patientId, String providerId) throws Exception {
        return post(s, "/api/v1/patients/" + patientId + "/provider-assignments",
                "{\"providerUserId\":\"%s\"}".formatted(providerId));
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
