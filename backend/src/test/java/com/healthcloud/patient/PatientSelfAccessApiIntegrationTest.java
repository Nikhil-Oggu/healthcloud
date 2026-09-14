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
 * Phase 3 slice 12 — patient self-service access (§12.1, §21). The seeded {@code patient@} login is linked to
 * the patient profile Sam Sample. A PATIENT-role user is gated to their own record: they list/read only their
 * own patient, and (inherited through {@link PatientAccessGuard}) only their own requests and consent — every
 * other patient in the tenant is a secure 404, never a 403 that would confirm it exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PatientSelfAccessApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void a_patient_lists_only_their_own_profile() throws Exception {
        Session patient = loginWithCsrf("patient@northcare.example.org");
        String body = get(patient.session, "/api/v1/patients").body();
        assertTrue(body.contains("Sam Sample"), "the patient sees their own profile");
        assertFalse(body.contains("Fern Fixture"), "the patient does not see other patients");
        assertFalse(body.contains("Mock Muller"), "the patient does not see other patients");
    }

    @Test
    void a_patient_can_read_their_own_record_but_not_another() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "Sam Sample");
        String fernId = patientId(coordinator, "Fern Fixture");

        Session patient = loginWithCsrf("patient@northcare.example.org");
        assertEquals(200, get(patient.session, "/api/v1/patients/" + samId).statusCode(),
                "the patient can read their own record");
        assertEquals(404, get(patient.session, "/api/v1/patients/" + fernId).statusCode(),
                "another patient's record is a secure 404");
    }

    @Test
    void a_patient_sees_only_their_own_requests() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "Sam Sample");
        String fernId = patientId(coordinator, "Fern Fixture");
        String ownRequest = newRequest(coordinator, samId);
        String otherRequest = newRequest(coordinator, fernId);

        Session patient = loginWithCsrf("patient@northcare.example.org");
        String list = get(patient.session, "/api/v1/requests").body();
        assertTrue(list.contains(ownRequest), "the patient sees a request about themselves");
        assertFalse(list.contains(otherRequest), "the patient does not see another patient's request");

        assertEquals(200, get(patient.session, "/api/v1/requests?patientId=" + samId).statusCode());
        assertEquals(404, get(patient.session, "/api/v1/requests?patientId=" + fernId).statusCode(),
                "filtering by another patient is a secure 404");
        assertEquals(404, get(patient.session, "/api/v1/requests/" + otherRequest).statusCode());
    }

    @Test
    void a_patient_reads_their_own_consent_but_not_anothers() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "Sam Sample");
        String fernId = patientId(coordinator, "Fern Fixture");

        Session patient = loginWithCsrf("patient@northcare.example.org");
        assertEquals(200, get(patient.session, "/api/v1/patients/" + samId + "/consent-directives").statusCode());
        assertEquals(404, get(patient.session, "/api/v1/patients/" + fernId + "/consent-directives").statusCode(),
                "another patient's consent list is a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String newRequest(Session s, String patientId) throws Exception {
        HttpResponse<String> created = post(s, "/api/v1/requests",
                "{\"patientId\":\"%s\",\"type\":\"CLAIM_SUPPORT\",\"title\":\"Self-access test\"}"
                        .formatted(patientId));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    private String patientId(Session s, String fullName) throws Exception {
        String body = get(s.session, "/api/v1/patients").body();
        Matcher m = Pattern.compile(
                "\\{\"id\":\"([0-9a-fA-F-]{36})\"[^}]*\"fullName\":\"" + Pattern.quote(fullName) + "\"")
                .matcher(body);
        assertTrue(m.find(), "expected a patient named " + fullName + " in: " + body);
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
