package com.healthcloud.request;

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
 * Phase 3 slice 11 — the object/relationship gate (§21 layer 6) applied to service requests. A request is
 * "about" a patient, so a PROVIDER may read or act on it only when actively assigned to that patient — the
 * same rule that already gates the patient object. An unassigned provider gets a secure 404 (never a 403 that
 * would confirm the request exists), and the unfiltered list shows only their assigned patients' requests;
 * coordinators/admins keep broad tenant access.
 *
 * <p>Relies on the seeded relationships: provider Dana is assigned to Sam Sample (NC-0001) and Fern Fixture
 * (NC-0002) but NOT Mock Muller (NC-0003); the coordinator can reach all three.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class RequestRelationshipGateApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void a_provider_can_reach_a_request_for_an_assigned_patient() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "NC-0001"); // Dana is assigned to Sam
        String requestId = newRequest(coordinator, samId);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        assertEquals(200, get(provider.session, "/api/v1/requests/" + requestId).statusCode());
        assertEquals(200, get(provider.session, "/api/v1/requests/" + requestId + "/history").statusCode());
        assertEquals(200, get(provider.session, "/api/v1/requests/" + requestId + "/comments").statusCode());
        assertEquals(200, get(provider.session, "/api/v1/requests/" + requestId + "/assignment").statusCode());
        assertTrue(get(provider.session, "/api/v1/requests?patientId=" + samId).body().contains(requestId),
                "the filtered list includes the assigned patient's request");
    }

    @Test
    void a_provider_gets_a_secure_404_for_a_request_about_an_unassigned_patient() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String mockId = patientId(coordinator, "NC-0003"); // Dana is NOT assigned to Mock
        String requestId = newRequest(coordinator, mockId);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        assertEquals(404, get(provider.session, "/api/v1/requests/" + requestId).statusCode());
        assertEquals(404, get(provider.session, "/api/v1/requests/" + requestId + "/history").statusCode());
        assertEquals(404, get(provider.session, "/api/v1/requests/" + requestId + "/comments").statusCode());
        assertEquals(404, get(provider.session, "/api/v1/requests/" + requestId + "/assignment").statusCode());
        assertEquals(404, get(provider.session, "/api/v1/requests?patientId=" + mockId).statusCode(),
                "filtering by an unreachable patient is a secure 404, like GET /patients/{id}");
    }

    @Test
    void a_providers_unfiltered_list_shows_only_their_assigned_patients_requests() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "NC-0001");
        String mockId = patientId(coordinator, "NC-0003");
        String assignedRequest = newRequest(coordinator, samId);
        String hiddenRequest = newRequest(coordinator, mockId);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        String body = get(provider.session, "/api/v1/requests").body();
        assertTrue(body.contains(assignedRequest), "an assigned patient's request is listed");
        assertFalse(body.contains(hiddenRequest), "an unassigned patient's request is hidden");
    }

    @Test
    void a_coordinator_sees_all_requests_broadly() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "NC-0001");
        String mockId = patientId(coordinator, "NC-0003");
        String samRequest = newRequest(coordinator, samId);
        String mockRequest = newRequest(coordinator, mockId);

        String all = get(coordinator.session, "/api/v1/requests").body();
        assertTrue(all.contains(samRequest) && all.contains(mockRequest), "a coordinator sees both");
        assertEquals(200, get(coordinator.session, "/api/v1/requests?patientId=" + mockId).statusCode(),
                "a coordinator may filter by any patient in the tenant");
    }

    @Test
    void a_provider_cannot_comment_on_or_transition_a_request_for_an_unassigned_patient() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String mockId = patientId(coordinator, "NC-0003");
        String requestId = newRequest(coordinator, mockId);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        assertEquals(404, post(provider, "/api/v1/requests/" + requestId + "/comments",
                "{\"body\":\"should not be allowed\"}").statusCode(), "comment write is gated");
        assertEquals(404, patch(provider, "/api/v1/requests/" + requestId + "/status",
                "{\"targetStatus\":\"SUBMITTED\",\"expectedVersion\":0}").statusCode(), "transition is gated");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String newRequest(Session s, String patientId) throws Exception {
        HttpResponse<String> created = post(s, "/api/v1/requests",
                "{\"patientId\":\"%s\",\"type\":\"CLAIM_SUPPORT\",\"title\":\"Gate test request\"}"
                        .formatted(patientId));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    private String patientId(Session s, String mrn) throws Exception {
        String body = get(s.session, "/api/v1/patients").body();
        Pattern p = Pattern.compile(
                "\\{\"id\":\"([0-9a-fA-F-]{36})\"[^}]*\"medicalRecordNumber\":\"" + Pattern.quote(mrn) + "\"");
        Matcher m = p.matcher(body);
        assertTrue(m.find(), "expected a patient with MRN " + mrn + " in: " + body);
        return m.group(1);
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
        return send("POST", s, path, json);
    }

    private HttpResponse<String> patch(Session s, String path, String json) throws Exception {
        return send("PATCH", s, path, json);
    }

    private HttpResponse<String> send(String method, Session s, String path, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json))
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
