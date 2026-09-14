package com.healthcloud.consent;

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
 * Phase 3 slice 13 — patient self-service consent (§22.1). The seeded {@code patient@} login is linked to the
 * patient profile Sam Sample, so a PATIENT may record and revoke consent directives for their OWN record but
 * not for anyone else's (a secure 404, via {@link com.healthcloud.patient.PatientAccessGuard}). Providers still
 * cannot write consent at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PatientSelfConsentApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern EXPECTED_VERSION = Pattern.compile("\"expectedVersion\":(\\d+)");

    private static final String GRANT_DEMOGRAPHICS = """
            {"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"DEMOGRAPHICS_CONTACT",\
            "scopeType":"ORGANIZATION"}""";

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base(String patientId) {
        return "/api/v1/patients/" + patientId + "/consent-directives";
    }

    @Test
    void a_patient_records_and_revokes_consent_on_their_own_record() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "Sam Sample");

        Session patient = loginWithCsrf("patient@northcare.example.org");
        HttpResponse<String> recorded = post(patient, base(samId), GRANT_DEMOGRAPHICS);
        assertEquals(201, recorded.statusCode(), recorded.body());
        assertTrue(recorded.body().contains("\"status\":\"ACTIVE\""));
        String directiveId = firstId(recorded.body());
        long expected = expectedVersion(recorded.body());

        HttpResponse<String> revoked = post(patient, base(samId) + "/" + directiveId + "/revoke",
                "{\"expectedVersion\":%d}".formatted(expected));
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertTrue(revoked.body().contains("\"status\":\"REVOKED\""));
    }

    @Test
    void a_patient_cannot_record_consent_for_another_patient() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String fernId = patientId(coordinator, "Fern Fixture");

        Session patient = loginWithCsrf("patient@northcare.example.org");
        HttpResponse<String> denied = post(patient, base(fernId), GRANT_DEMOGRAPHICS);
        assertEquals(404, denied.statusCode(), "another patient's record is a secure 404, not a 403");
    }

    @Test
    void a_provider_still_cannot_write_consent() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "Sam Sample"); // Dana is assigned to Sam, yet still may not write

        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> denied = post(provider, base(samId), GRANT_DEMOGRAPHICS);
        assertEquals(403, denied.statusCode(), "a provider is not a consent write role");
        assertTrue(denied.body().contains("ACCESS_DENIED"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
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
