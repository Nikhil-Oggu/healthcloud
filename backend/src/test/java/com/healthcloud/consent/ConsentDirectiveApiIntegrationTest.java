package com.healthcloud.consent;

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
 * Phase 3 slice 1 — consent directive lifecycle against a real server (§22). Recording a directive makes it
 * ACTIVE and listable; re-recording the same natural key supersedes the prior version; revocation removes it
 * from the current set with history retained; writes are staff-gated; reads are open to same-tenant users;
 * tenant scoping (secure 404), optimistic locking (409), and validation (400) all hold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ConsentDirectiveApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern EXPECTED_VERSION = Pattern.compile("\"expectedVersion\":(\\d+)");
    private static final Pattern DOMAIN_VERSION = Pattern.compile("\"version\":(\\d+)");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String consentPath(String patientId) {
        return "/api/v1/patients/" + patientId + "/consent-directives";
    }

    private static final String ORG_GRANT = """
            {"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT",
             "scopeType":"ORGANIZATION"}""";

    @Test
    void recording_a_directive_makes_it_active_and_listed() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        HttpResponse<String> created = post(coordinator, consentPath(patientId), ORG_GRANT);
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"status\":\"ACTIVE\""));
        assertEquals(1, domainVersion(created.body()), "first version of a new directive is 1");

        String current = get(coordinator.session, consentPath(patientId)).body();
        assertTrue(current.contains("CARE_COORDINATION") && current.contains("CLINICAL_CONTEXT"));
    }

    @Test
    void re_recording_the_same_natural_key_supersedes_the_prior_version() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        assertEquals(201, post(coordinator, consentPath(patientId), ORG_GRANT).statusCode());
        // A DENY for the same purpose+category+scope is a modification → version 2, previous SUPERSEDED.
        HttpResponse<String> changed = post(coordinator, consentPath(patientId), """
                {"effect":"DENY","purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT",
                 "scopeType":"ORGANIZATION"}""");
        assertEquals(201, changed.statusCode(), changed.body());
        assertEquals(2, domainVersion(changed.body()));
        assertTrue(changed.body().contains("\"effect\":\"DENY\""));

        // Current set has exactly one row for this key; history shows both, one SUPERSEDED.
        String current = get(coordinator.session, consentPath(patientId)).body();
        assertEquals(1, count(current, "CARE_COORDINATION"), "only the newest version is current");
        String history = get(coordinator.session, consentPath(patientId) + "?includeHistory=true").body();
        assertEquals(2, count(history, "CARE_COORDINATION"));
        assertTrue(history.contains("SUPERSEDED"));
    }

    @Test
    void revoking_a_directive_removes_it_from_the_current_set_but_keeps_history() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        HttpResponse<String> created = post(coordinator, consentPath(patientId), ORG_GRANT);
        String directiveId = firstId(created.body());
        long expected = expectedVersion(created.body());

        HttpResponse<String> revoked = post(coordinator,
                consentPath(patientId) + "/" + directiveId + "/revoke",
                "{\"expectedVersion\":%d}".formatted(expected));
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertTrue(revoked.body().contains("\"status\":\"REVOKED\""));

        assertFalse(get(coordinator.session, consentPath(patientId)).body().contains("CARE_COORDINATION"),
                "a revoked directive is no longer in the current set");
        assertTrue(get(coordinator.session, consentPath(patientId) + "?includeHistory=true").body()
                .contains("REVOKED"), "history retains the revoked directive");
    }

    @Test
    void a_provider_scoped_directive_names_the_provider() throws Exception {
        String providerId = meId(loginWithCsrf("provider@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        HttpResponse<String> created = post(coordinator, consentPath(patientId), """
                {"effect":"GRANT","purpose":"CLAIM_PROCESSING","dataCategory":"CLAIMS_BENEFITS",
                 "scopeType":"PROVIDER","scopeRefId":"%s"}""".formatted(providerId));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains(providerId) && created.body().contains("\"scopeType\":\"PROVIDER\""));
    }

    @Test
    void a_read_only_role_cannot_record_but_can_read() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);
        assertEquals(201, post(coordinator, consentPath(patientId), ORG_GRANT).statusCode());

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        assertEquals(403, post(reviewer, consentPath(patientId), ORG_GRANT).statusCode(),
                "a claims reviewer is not a consent write role");
        assertEquals(200, get(reviewer.session, consentPath(patientId)).statusCode(),
                "same-tenant users may read consent metadata in this slice");
    }

    @Test
    void a_stale_version_on_revoke_is_a_conflict() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);
        String directiveId = firstId(post(coordinator, consentPath(patientId), ORG_GRANT).body());

        HttpResponse<String> stale = post(coordinator,
                consentPath(patientId) + "/" + directiveId + "/revoke", "{\"expectedVersion\":999}");
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("CONFLICT") && !stale.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void a_non_current_directive_cannot_be_revoked_again() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);
        HttpResponse<String> created = post(coordinator, consentPath(patientId), ORG_GRANT);
        String directiveId = firstId(created.body());
        long expected = expectedVersion(created.body());
        assertEquals(200, post(coordinator, consentPath(patientId) + "/" + directiveId + "/revoke",
                "{\"expectedVersion\":%d}".formatted(expected)).statusCode());

        // Already REVOKED → not current → invalid transition (checked before the version).
        HttpResponse<String> again = post(coordinator, consentPath(patientId) + "/" + directiveId + "/revoke",
                "{\"expectedVersion\":0}");
        assertEquals(409, again.statusCode());
        assertTrue(again.body().contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void validation_rejects_a_scope_mismatch() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = newPatientId(coordinator);

        // PROVIDER scope without a scopeRefId → 400.
        HttpResponse<String> missingRef = post(coordinator, consentPath(patientId), """
                {"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT",
                 "scopeType":"PROVIDER"}""");
        assertEquals(400, missingRef.statusCode());
        assertTrue(missingRef.body().contains("VALIDATION_FAILED"));

        // Missing a required field (effect) → 400.
        HttpResponse<String> missingEffect = post(coordinator, consentPath(patientId), """
                {"purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT","scopeType":"ORGANIZATION"}""");
        assertEquals(400, missingEffect.statusCode());
    }

    @Test
    void cannot_touch_another_tenants_patient() throws Exception {
        Session green = loginWithCsrf("coordinator@greenvalley.example.org");
        String greenPatientId = newPatientId(green);

        Session north = loginWithCsrf("coordinator@northcare.example.org");
        assertEquals(404, get(north.session, consentPath(greenPatientId)).statusCode(),
                "reading another tenant's patient consent must be a secure 404");
        assertEquals(404, post(north, consentPath(greenPatientId), ORG_GRANT).statusCode(),
                "recording against another tenant's patient must be a secure 404");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    /** Create a fresh patient in the caller's tenant so each test has an isolated consent set. */
    private String newPatientId(Session s) throws Exception {
        String mrn = "CN-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"%s","fullName":"Consent Test Patient","dateOfBirth":"1990-01-01"}"""
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

    /** The signed-in user's own id (from /me). */
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

    private static int domainVersion(String json) {
        Matcher m = DOMAIN_VERSION.matcher(json);
        assertTrue(m.find(), "expected a version in: " + json);
        return Integer.parseInt(m.group(1));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
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
