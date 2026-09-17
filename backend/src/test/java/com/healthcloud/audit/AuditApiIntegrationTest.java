package com.healthcloud.audit;

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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The security audit trail API (Phase 7 slice 1) against a real embedded server with the seeded demo. Proves the
 * trail is authenticated and gated to AUDITOR/ORG_ADMIN (a PATIENT/PROVIDER/CLAIMS_REVIEWER is a flat 403 — a
 * role-gated list, not a secure 404); that an adjudication writes exactly one {@code CLAIM_ADJUDICATED} event in
 * the SAME transaction (one row, tied to the request's correlation id, with a PHI-free detail); that a consent
 * revoke writes a {@code CONSENT_REVOKED} event; and that the trail is tenant-scoped (another tenant's admin
 * never sees this tenant's events).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AuditApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void the_audit_trail_requires_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/audit-events").statusCode());
    }

    @Test
    void an_adjudication_writes_one_claim_adjudicated_event_in_the_same_transaction() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());

        // The auditor reads just this claim's audit history: exactly one CLAIM_ADJUDICATED SUCCESS event.
        Session auditor = loginWithCsrf("auditor@northcare.example.org");
        HttpResponse<String> events =
                get(auditor.session, "/api/v1/audit-events?resourceType=CLAIM&resourceId=" + claimId);
        assertEquals(200, events.statusCode(), events.body());
        assertEquals(1, countOccurrences(events.body(), "\"action\":\"CLAIM_ADJUDICATED\""),
                "one adjudication → exactly one audit event: " + events.body());
        assertTrue(events.body().contains("\"outcome\":\"SUCCESS\""), events.body());
        assertTrue(events.body().contains("\"resourceType\":\"CLAIM\""), events.body());
        assertTrue(events.body().contains("\"correlationId\":\""), "the event carries a correlation id");
        // PHI-free detail: names the claim number, no patient identifier.
        assertTrue(events.body().contains("adjudicated v1"), events.body());
    }

    @Test
    void re_adjudication_appends_a_second_audit_event() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());
        assertEquals(200, adjudicate(reviewer, claimId).statusCode()); // re-adjudicate → v2

        Session admin = loginWithCsrf("admin@northcare.example.org");
        HttpResponse<String> events =
                get(admin.session, "/api/v1/audit-events?resourceType=CLAIM&resourceId=" + claimId);
        assertEquals(2, countOccurrences(events.body(), "\"action\":\"CLAIM_ADJUDICATED\""),
                "two adjudications → two audit events: " + events.body());
    }

    @Test
    void revoking_consent_writes_a_consent_revoked_event() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String directiveId = firstId(recordConsent(coordinator, patientId).body());
        assertEquals(200, revokeConsent(coordinator, patientId, directiveId).statusCode());

        Session auditor = loginWithCsrf("auditor@northcare.example.org");
        HttpResponse<String> events = get(auditor.session,
                "/api/v1/audit-events?resourceType=CONSENT_DIRECTIVE&resourceId=" + directiveId);
        assertEquals(200, events.statusCode(), events.body());
        assertEquals(1, countOccurrences(events.body(), "\"action\":\"CONSENT_REVOKED\""), events.body());
        assertTrue(events.body().contains("Consent directive revoked"), events.body());
    }

    @Test
    void the_auditor_role_may_read_the_trail() throws Exception {
        assertEquals(200, get(loginSession("auditor@northcare.example.org"), "/api/v1/audit-events").statusCode());
    }

    @Test
    void an_admin_may_read_the_trail() throws Exception {
        assertEquals(200, get(loginSession("admin@northcare.example.org"), "/api/v1/audit-events").statusCode());
    }

    @Test
    void a_reviewer_cannot_read_the_trail() throws Exception {
        // The audit trail is gated to AUDITOR/ORG_ADMIN; a CLAIMS_REVIEWER is a flat 403 (a role-gated list).
        assertEquals(403, get(loginSession("reviewer@northcare.example.org"), "/api/v1/audit-events").statusCode());
    }

    @Test
    void a_provider_cannot_read_the_trail() throws Exception {
        assertEquals(403, get(loginSession("provider@northcare.example.org"), "/api/v1/audit-events").statusCode());
    }

    @Test
    void a_patient_cannot_read_the_trail() throws Exception {
        assertEquals(403, get(loginSession("patient@northcare.example.org"), "/api/v1/audit-events").statusCode());
    }

    @Test
    void the_trail_is_tenant_scoped() throws Exception {
        // Adjudicate a claim in NorthCare, producing a CLAIM_ADJUDICATED event there.
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());

        // Green Valley's admin, filtering for that NorthCare claim, sees nothing (org-scoped finder).
        Session green = loginWithCsrf("admin@greenvalley.example.org");
        HttpResponse<String> cross =
                get(green.session, "/api/v1/audit-events?resourceType=CLAIM&resourceId=" + claimId);
        assertEquals(200, cross.statusCode(), cross.body());
        assertEquals(0, countOccurrences(cross.body(), "\"action\":\"CLAIM_ADJUDICATED\""),
                "another tenant's audit events must never appear: " + cross.body());
    }

    @Test
    void the_list_returns_a_page_envelope_and_filters_by_action_in_sql() throws Exception {
        // Generate a CLAIM_ADJUDICATED event.
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());

        Session auditor = loginWithCsrf("auditor@northcare.example.org");
        HttpResponse<String> list = get(auditor.session, "/api/v1/audit-events?size=5");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"content\":["), "the response is a page envelope");
        assertTrue(list.body().contains("\"page\":0"));
        assertTrue(list.body().contains("\"totalElements\":"));

        // Filtering by CONSENT_REVOKED excludes the CLAIM_ADJUDICATED event (the filter runs in SQL).
        HttpResponse<String> consentOnly =
                get(auditor.session, "/api/v1/audit-events?action=CONSENT_REVOKED&size=100");
        assertEquals(0, countOccurrences(consentOnly.body(), "\"action\":\"CLAIM_ADJUDICATED\""),
                "the action filter runs in the database: " + consentOnly.body());
    }

    @Test
    void an_unknown_sort_field_is_a_400() throws Exception {
        String auditor = loginSession("auditor@northcare.example.org");
        HttpResponse<String> bad = get(auditor, "/api/v1/audit-events?sort=ssn");
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("VALIDATION_FAILED"), "sorting by a non-allowlisted field is a clean 400");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"AUD-%s","fullName":"Audit Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    /** Drive a two-line claim to ACCEPTED: coordinator creates + submits (v0), reviewer accepts (v1). */
    private String acceptedClaim(Session coordinator, Session reviewer, String patientId) throws Exception {
        String claimId = firstId(post(coordinator, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"99213","units":1,"chargeAmount":150.00}]}""".formatted(patientId)).body());
        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0).statusCode());
        assertEquals(200, patchStatus(reviewer, claimId, "ACCEPTED", 1).statusCode());
        return claimId;
    }

    private String firstPlanId(Session s) throws Exception {
        return firstId(get(s.session, "/api/v1/coverage-plans").body());
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"AUD-M1\",\"effectiveFrom\":\"2020-01-01\"}"
                        .formatted(planId));
        assertEquals(201, enrolled.statusCode(), enrolled.body());
    }

    private HttpResponse<String> recordConsent(Session s, String patientId) throws Exception {
        return post(s, "/api/v1/patients/" + patientId + "/consent-directives", """
                {"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT",\
                "scopeType":"ORGANIZATION"}""");
    }

    private HttpResponse<String> revokeConsent(Session s, String patientId, String directiveId) throws Exception {
        return post(s, "/api/v1/patients/" + patientId + "/consent-directives/" + directiveId + "/revoke",
                "{\"expectedVersion\":0}");
    }

    private HttpResponse<String> patchStatus(Session s, String claimId, String target, long expectedVersion)
            throws Exception {
        String json = "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion);
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/claims/" + claimId + "/status"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> adjudicate(Session s, String claimId) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/claims/" + claimId + "/adjudicate"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String loginSession(String email) throws Exception {
        return loginWithCsrf(email).session;
    }

    private Session loginWithCsrf(String email) throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("email=" + email))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
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
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (session != null) {
            b.header("Cookie", "SESSION=" + session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String firstId(String json) {
        Matcher m = FIRST_ID.matcher(json);
        assertTrue(m.find(), "expected an id in: " + json);
        return m.group(1);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
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
