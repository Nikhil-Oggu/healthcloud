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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 2 slice 7 — request comments against a real server: add + list (oldest first), participant-role
 * authorization (read-only roles denied), tenant scoping (another tenant's request → secure 404), and
 * body validation. Exercises the CSRF cookie→header handshake on the create.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class RequestCommentApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Create a DRAFT request in NorthCare and return its id, using a coordinator session. */
    private String createRequest(Session s) throws Exception {
        String patientId = firstId(get(s.session, "/api/v1/patients").body());
        HttpResponse<String> created = post(s, "/api/v1/requests", """
                {"patientId":"%s","type":"CLAIM_SUPPORT","title":"Help with a claim"}""".formatted(patientId));
        assertEquals(201, created.statusCode());
        return firstId(created.body());
    }

    @Test
    void participant_adds_a_comment_and_it_lists_oldest_first() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String requestId = createRequest(coordinator);

        HttpResponse<String> first = post(coordinator, "/api/v1/requests/" + requestId + "/comments",
                """
                {"body":"First note"}""");
        assertEquals(201, first.statusCode());
        assertTrue(first.body().contains("\"body\":\"First note\""));

        // A different participant (the patient) can also comment on the same request.
        Session patient = loginWithCsrf("patient@northcare.example.org");
        HttpResponse<String> second = post(patient, "/api/v1/requests/" + requestId + "/comments",
                """
                {"body":"Second note"}""");
        assertEquals(201, second.statusCode());

        HttpResponse<String> list = get(coordinator.session, "/api/v1/requests/" + requestId + "/comments");
        assertEquals(200, list.statusCode());
        int firstPos = list.body().indexOf("First note");
        int secondPos = list.body().indexOf("Second note");
        assertTrue(firstPos >= 0 && secondPos >= 0, "both comments should be present");
        assertTrue(firstPos < secondPos, "comments should be oldest first");
    }

    @Test
    void a_read_only_role_cannot_comment() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String requestId = createRequest(coordinator);

        // CLAIMS_REVIEWER is a read-only workflow role — it may read but not comment.
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        HttpResponse<String> denied = post(reviewer, "/api/v1/requests/" + requestId + "/comments",
                """
                {"body":"I should not be able to write this"}""");
        assertEquals(403, denied.statusCode());
        assertTrue(denied.body().contains("ACCESS_DENIED"));
    }

    @Test
    void cannot_comment_on_another_tenants_request() throws Exception {
        String northRequestId = createRequest(loginWithCsrf("coordinator@northcare.example.org"));

        // A Green Valley user must not be able to comment on a NorthCare request → secure 404.
        Session green = loginWithCsrf("coordinator@greenvalley.example.org");
        HttpResponse<String> cross = post(green, "/api/v1/requests/" + northRequestId + "/comments",
                """
                {"body":"Cross-tenant"}""");
        assertEquals(404, cross.statusCode(), "another tenant's request must be a secure 404");

        // And reading it across tenants is also a 404.
        assertEquals(404, get(green.session, "/api/v1/requests/" + northRequestId + "/comments").statusCode());
    }

    @Test
    void a_blank_body_is_rejected_with_400() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String requestId = createRequest(coordinator);

        HttpResponse<String> blank = post(coordinator, "/api/v1/requests/" + requestId + "/comments",
                """
                {"body":"  "}""");
        assertEquals(400, blank.statusCode());
        assertTrue(blank.body().contains("VALIDATION_FAILED"));
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
