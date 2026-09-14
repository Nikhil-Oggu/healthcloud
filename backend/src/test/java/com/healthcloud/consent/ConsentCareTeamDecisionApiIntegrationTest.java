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
 * Phase 3 slice 8 — the CARE_TEAM consent scope, end-to-end (§22.5). A CARE_TEAM-scoped directive applies to
 * the patient's care team = the providers and coordinators actively assigned to them. The decision therefore
 * turns on care-team <b>membership</b>, not role: a coordinator with broad tenant access is still denied a
 * CARE_TEAM grant until they are assigned to the patient, and an assigned provider is granted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ConsentCareTeamDecisionApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern EFFECT = Pattern.compile("\"effect\":\"(GRANT|DENY)\"");

    private static final String CARE_TEAM_GRANT = """
            {"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT",
             "scopeType":"CARE_TEAM"}""";

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String consentBase(String patientId) {
        return "/api/v1/patients/" + patientId + "/consent-directives";
    }

    private String decisionPath(String patientId) {
        return consentBase(patientId)
                + "/decision?purpose=CARE_COORDINATION&dataCategory=CLINICAL_CONTEXT";
    }

    @Test
    void a_care_team_grant_reaches_an_assigned_coordinator_but_not_an_unassigned_one() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String coordinatorId = meId(coordinator);
        String patientId = newPatientId(coordinator);
        assertEquals(201, post(coordinator, consentBase(patientId), CARE_TEAM_GRANT).statusCode());

        // The coordinator has broad access (the gate passes) but is not yet on this patient's care team.
        assertEquals("DENY", effect(get(coordinator.session, decisionPath(patientId)).body()),
                "a CARE_TEAM grant does not reach a non-member, even one with broad access");

        // Put the coordinator on the care team → the same CARE_TEAM grant now applies.
        assertEquals(201, post(coordinator, "/api/v1/patients/" + patientId + "/coordinator-assignments",
                "{\"coordinatorUserId\":\"%s\"}".formatted(coordinatorId)).statusCode());

        HttpResponse<String> granted = get(coordinator.session, decisionPath(patientId));
        assertEquals("GRANT", effect(granted.body()), "an assigned coordinator is on the care team");
        assertTrue(granted.body().contains("\"decidingScope\":\"CARE_TEAM\""));
    }

    @Test
    void a_care_team_grant_reaches_an_assigned_provider() throws Exception {
        String providerId = meId(loginWithCsrf("provider@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        // The provider must be assigned to read the patient at all (the object/relationship gate).
        assertEquals(201, post(coordinator, "/api/v1/patients/" + patientId + "/provider-assignments",
                "{\"providerUserId\":\"%s\"}".formatted(providerId)).statusCode());
        assertEquals(201, post(coordinator, consentBase(patientId), CARE_TEAM_GRANT).statusCode());

        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> decision = get(provider.session, decisionPath(patientId));
        assertEquals("GRANT", effect(decision.body()), "an assigned provider is on the care team");
        assertTrue(decision.body().contains("\"decidingScope\":\"CARE_TEAM\""));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String newPatientId(Session s) throws Exception {
        String mrn = "CT-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"%s","fullName":"Care Team Test Patient","dateOfBirth":"1990-01-01"}"""
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

    private static String effect(String json) {
        Matcher m = EFFECT.matcher(json);
        assertTrue(m.find(), "expected an effect in: " + json);
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
