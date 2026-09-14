package com.healthcloud.patient;

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
 * Phase 3 slice 6 (extended in slice 7) — the object/relationship gate (§21 layer 6) applies to the
 * patient-<i>nested</i> endpoints, not just the patient read. A PROVIDER who is not assigned to a patient gets
 * a secure 404 (§21.5) on that patient's consent directives, consent decision, provider assignments, and
 * coordinator assignments too — so a nested route cannot be used to side-step the gate. Assigning the provider
 * restores access; coordinators/admins keep broad access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PatientNestedEndpointGateApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String consentList(String patientId) {
        return "/api/v1/patients/" + patientId + "/consent-directives";
    }

    private String decision(String patientId) {
        return "/api/v1/patients/" + patientId
                + "/consent-directives/decision?purpose=CARE_COORDINATION&dataCategory=CLINICAL_CONTEXT";
    }

    private String assignments(String patientId) {
        return "/api/v1/patients/" + patientId + "/provider-assignments";
    }

    private String coordinatorAssignments(String patientId) {
        return "/api/v1/patients/" + patientId + "/coordinator-assignments";
    }

    private String documents(String patientId) {
        return "/api/v1/patients/" + patientId + "/documents";
    }

    @Test
    void an_unassigned_provider_is_a_secure_404_on_every_nested_endpoint() throws Exception {
        loginWithCsrf("provider@northcare.example.org"); // ensure the provider exists
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        for (String path : List.of(consentList(patientId), decision(patientId), assignments(patientId), coordinatorAssignments(patientId), documents(patientId))) {
            HttpResponse<String> denied = get(provider.session, path);
            assertEquals(404, denied.statusCode(),
                    "an unassigned provider must be a secure 404 on " + path + " (got: " + denied.body() + ")");
            assertTrue(denied.body().contains("NOT_FOUND"), "the denial is a secure 404, not a 403: " + path);
        }
    }

    @Test
    void assigning_the_provider_opens_the_nested_endpoints() throws Exception {
        String providerId = meId(loginWithCsrf("provider@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);
        assertEquals(201, assign(coordinator, patientId, providerId).statusCode());

        Session provider = loginWithCsrf("provider@northcare.example.org");
        for (String path : List.of(consentList(patientId), decision(patientId), assignments(patientId), coordinatorAssignments(patientId), documents(patientId))) {
            assertEquals(200, get(provider.session, path).statusCode(),
                    "an assigned provider may read " + path);
        }
    }

    @Test
    void a_coordinator_reaches_the_nested_endpoints_without_any_assignment() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        for (String path : List.of(consentList(patientId), decision(patientId), assignments(patientId), coordinatorAssignments(patientId), documents(patientId))) {
            assertEquals(200, get(coordinator.session, path).statusCode(),
                    "a coordinator has broad access to " + path);
        }
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String newPatientId(Session s) throws Exception {
        String mrn = "NG-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"%s","fullName":"Nested Gate Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(mrn));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    private HttpResponse<String> assign(Session coordinator, String patientId, String providerId) throws Exception {
        return post(coordinator, assignments(patientId), "{\"providerUserId\":\"%s\"}".formatted(providerId));
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
