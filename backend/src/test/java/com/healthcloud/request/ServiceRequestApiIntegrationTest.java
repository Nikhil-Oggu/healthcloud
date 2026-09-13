package com.healthcloud.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 2 slice 4 — creating and reading service requests against a real server: create a DRAFT (with
 * the initial status-history row written in the same transaction), tenant/patient scoping, role
 * authorization, validation, and CSRF on the create.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ServiceRequestApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    @Autowired ServiceRequestRepository requests;
    @Autowired RequestStatusHistoryRepository history;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void coordinator_creates_a_draft_request_and_an_initial_history_row() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(get(s.session, "/api/v1/patients").body());

        HttpResponse<String> created = post(s, "/api/v1/requests", """
                {"patientId":"%s","type":"CLAIM_SUPPORT","title":"Help with a claim"}""".formatted(patientId));
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"status\":\"DRAFT\""), "new request should be DRAFT");
        assertTrue(created.body().contains("\"priority\":\"NORMAL\""), "priority should default to NORMAL");

        String requestId = firstId(created.body());

        // It is readable back within the tenant, and shows in the list (both overall and per-patient).
        assertEquals(200, get(s.session, "/api/v1/requests/" + requestId).statusCode());
        assertTrue(get(s.session, "/api/v1/requests").body().contains(requestId));
        assertTrue(get(s.session, "/api/v1/requests?patientId=" + patientId).body().contains(requestId));

        // §31.6: the initial status-history row was written in the same transaction (null -> DRAFT).
        UUID orgId = requests.findById(UUID.fromString(requestId)).orElseThrow().getOrganizationId();
        var rows = history.findByOrganizationIdAndServiceRequestIdOrderByCreatedAtAsc(
                orgId, UUID.fromString(requestId));
        assertEquals(1, rows.size(), "exactly one creation history row");
        assertNull(rows.get(0).getFromStatus(), "creation row has no from-status");
        assertEquals(ServiceRequestStatus.DRAFT, rows.get(0).getToStatus());
    }

    @Test
    void cannot_create_a_request_for_another_tenants_patient() throws Exception {
        String greenPatientId = firstId(
                get(loginWithCsrf("admin@greenvalley.example.org").session, "/api/v1/patients").body());

        Session north = loginWithCsrf("coordinator@northcare.example.org");
        HttpResponse<String> cross = post(north, "/api/v1/requests", """
                {"patientId":"%s","type":"REFERRAL_REQUEST","title":"Cross-tenant"}""".formatted(greenPatientId));
        assertEquals(404, cross.statusCode(), "another tenant's patient must be a secure 404");
    }

    @Test
    void a_reviewer_cannot_create_a_request() throws Exception {
        Session s = loginWithCsrf("reviewer@northcare.example.org"); // CLAIMS_REVIEWER is not a create role
        HttpResponse<String> denied = post(s, "/api/v1/requests", """
                {"patientId":"%s","type":"CLAIM_SUPPORT","title":"Nope"}""".formatted(UUID.randomUUID()));
        assertEquals(403, denied.statusCode());
        assertTrue(denied.body().contains("ACCESS_DENIED"));
    }

    @Test
    void invalid_payloads_are_rejected_with_400() throws Exception {
        Session s = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(get(s.session, "/api/v1/patients").body());

        HttpResponse<String> blankTitle = post(s, "/api/v1/requests", """
                {"patientId":"%s","type":"CLAIM_SUPPORT","title":""}""".formatted(patientId));
        assertEquals(400, blankTitle.statusCode());
        assertTrue(blankTitle.body().contains("VALIDATION_FAILED"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

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
