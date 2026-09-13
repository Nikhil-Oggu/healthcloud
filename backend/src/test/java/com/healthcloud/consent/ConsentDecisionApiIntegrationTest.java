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
 * Phase 3 slice 2 — the consent + purpose decision engine over HTTP (§22.5). The decision is about the
 * CALLING actor: an org-wide grant applies to anyone, and adding a more-specific provider DENY flips that
 * same caller's decision. No applicable directive → deny by default; a bad purpose → 400; another tenant's
 * patient → secure 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ConsentDecisionApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern EFFECT = Pattern.compile("\"effect\":\"(GRANT|DENY)\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String decisionPath(String patientId) {
        return "/api/v1/patients/" + patientId
                + "/consent-directives/decision?purpose=CARE_COORDINATION&dataCategory=CLINICAL_CONTEXT";
    }

    private static final String ORG_GRANT = """
            {"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT",
             "scopeType":"ORGANIZATION"}""";

    @Test
    void an_organization_grant_is_visible_to_any_actor() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);
        assertEquals(201, post(coordinator, consentBase(patientId), ORG_GRANT).statusCode());

        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> decision = get(provider.session, decisionPath(patientId));
        assertEquals(200, decision.statusCode(), decision.body());
        assertEquals("GRANT", effect(decision.body()));
        assertTrue(decision.body().contains("\"decidingScope\":\"ORGANIZATION\""));
    }

    @Test
    void a_more_specific_provider_deny_flips_the_same_callers_decision() throws Exception {
        String providerId = meId(loginWithCsrf("provider@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        // Org-wide grant → the provider is granted.
        assertEquals(201, post(coordinator, consentBase(patientId), ORG_GRANT).statusCode());
        Session provider = loginWithCsrf("provider@northcare.example.org");
        assertEquals("GRANT", effect(get(provider.session, decisionPath(patientId)).body()));

        // Add a provider-specific DENY naming that provider → the SAME caller now gets DENY.
        assertEquals(201, post(coordinator, consentBase(patientId), """
                {"effect":"DENY","purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT",
                 "scopeType":"PROVIDER","scopeRefId":"%s"}""".formatted(providerId)).statusCode());

        HttpResponse<String> denied = get(provider.session, decisionPath(patientId));
        assertEquals("DENY", effect(denied.body()));
        assertTrue(denied.body().contains("\"decidingScope\":\"PROVIDER\""));
    }

    @Test
    void no_applicable_directive_is_deny_by_default() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        HttpResponse<String> decision = get(coordinator.session, decisionPath(patientId));
        assertEquals(200, decision.statusCode());
        assertEquals("DENY", effect(decision.body()));
        assertTrue(decision.body().contains("deny by default"));
    }

    @Test
    void an_unknown_purpose_is_a_400() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);
        HttpResponse<String> bad = get(coordinator.session, "/api/v1/patients/" + patientId
                + "/consent-directives/decision?purpose=BOGUS&dataCategory=CLINICAL_CONTEXT");
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void cannot_get_a_decision_for_another_tenants_patient() throws Exception {
        Session green = loginWithCsrf("coordinator@greenvalley.example.org");
        String greenPatientId = newPatientId(green);

        Session north = loginWithCsrf("provider@northcare.example.org");
        assertEquals(404, get(north.session, decisionPath(greenPatientId)).statusCode(),
                "a decision about another tenant's patient must be a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String consentBase(String patientId) {
        return "/api/v1/patients/" + patientId + "/consent-directives";
    }

    /** Create a fresh patient in the caller's tenant so each test has an isolated consent set. */
    private String newPatientId(Session s) throws Exception {
        String mrn = "CD-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"%s","fullName":"Decision Test Patient","dateOfBirth":"1990-01-01"}"""
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
