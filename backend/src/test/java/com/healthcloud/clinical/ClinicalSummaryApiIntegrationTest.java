package com.healthcloud.clinical;

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
 * Clinical summary API (Phase 4 slice 2) against a real embedded server with the seeded demo. Proves the
 * resource is authenticated and tenant-scoped, that the diagnosis is validated against the global code catalog
 * (unknown code → 400), and — the start of the Phase-4 §60 proof — that the free-text {@code narrative} is
 * consent-masked (deny-by-default) while the structured diagnosis <b>code</b> stays visible, so a caller can see
 * the coded, claim-relevant diagnosis without unrestricted medical context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ClinicalSummaryApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final String NARRATIVE = "Patient reviewed for ongoing diabetes management.";

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String summariesBase(String patientId) {
        return "/api/v1/patients/" + patientId + "/clinical-summaries";
    }

    private String createSummaryJson(String diagnosisCode) {
        return """
                {"summaryType":"ENCOUNTER","encounterDate":"2026-01-10","title":"Follow-up visit",
                 "diagnosisCode":"%s","narrative":"%s"}""".formatted(diagnosisCode, NARRATIVE);
    }

    @Test
    void clinical_summaries_require_authentication() throws Exception {
        HttpResponse<String> anon = get(null, "/api/v1/patients/" + UUID.randomUUID() + "/clinical-summaries");
        assertEquals(401, anon.statusCode());
    }

    @Test
    void create_returns_unmasked_but_a_read_masks_the_narrative_without_consent() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        // The create (write) response is unmasked — the author just supplied the narrative — and stores the
        // canonical diagnosis code.
        HttpResponse<String> created = post(coordinator, summariesBase(patientId), createSummaryJson("e11.9"));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains(NARRATIVE), "the write response returns the narrative");
        assertTrue(created.body().contains("\"diagnosisCode\":\"E11.9\""), "the catalog's canonical code is stored");
        assertTrue(created.body().contains("\"maskedFields\":[]"), "writes are not masked");

        // The read is field-safe: no consent yet → narrative masked (deny-by-default), coded diagnosis visible.
        HttpResponse<String> read = get(coordinator.session, summariesBase(patientId));
        assertEquals(200, read.statusCode());
        assertFalse(read.body().contains(NARRATIVE), "the narrative is withheld without consent");
        assertTrue(read.body().contains("\"narrative\":null"));
        assertTrue(read.body().contains("\"maskedFields\":[\"narrative\"]"));
        assertTrue(read.body().contains("\"diagnosisCode\":\"E11.9\""),
                "the structured diagnosis code stays visible — claim-relevant data without clinical narrative");
    }

    @Test
    void a_care_coordination_grant_reveals_the_narrative() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        assertEquals(201, post(coordinator, summariesBase(patientId), createSummaryJson("E11.9")).statusCode());

        // A CLINICAL_CONTEXT grant for care coordination reveals the narrative on the next read.
        assertEquals(201, post(coordinator, "/api/v1/patients/" + patientId + "/consent-directives", """
                {"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"CLINICAL_CONTEXT",
                 "scopeType":"ORGANIZATION"}""").statusCode());

        HttpResponse<String> read = get(coordinator.session, summariesBase(patientId));
        assertTrue(read.body().contains(NARRATIVE), "an applicable CLINICAL_CONTEXT grant reveals the narrative");
        assertTrue(read.body().contains("\"maskedFields\":[]"));
    }

    @Test
    void a_reviewer_sees_the_coded_diagnosis_but_not_the_narrative() throws Exception {
        // §60 proof (first half): a claims reviewer can reach the record and see the coded, claim-relevant
        // diagnosis, but the unrestricted clinical narrative is withheld (no consent grant → deny-by-default).
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        assertEquals(201, post(coordinator, summariesBase(patientId), createSummaryJson("E11.9")).statusCode());

        String reviewer = loginWithCsrf("reviewer@northcare.example.org").session;
        HttpResponse<String> read = get(reviewer, summariesBase(patientId));
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"diagnosisCode\":\"E11.9\""), "the reviewer sees the coded diagnosis");
        assertFalse(read.body().contains(NARRATIVE), "the reviewer does not see the clinical narrative");
        assertTrue(read.body().contains("\"maskedFields\":[\"narrative\"]"));
    }

    @Test
    void an_unknown_diagnosis_code_is_a_400() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        // A syntactically valid but non-existent code is a clean validation error, not a 500.
        HttpResponse<String> bad = post(coordinator, summariesBase(patientId), createSummaryJson("NOPE.0"));
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("VALIDATION_FAILED"));

        // A procedure (CPT) code is not a diagnosis — also rejected (diagnoses are ICD-10-CM).
        HttpResponse<String> procedure = post(coordinator, summariesBase(patientId), createSummaryJson("99213"));
        assertEquals(400, procedure.statusCode(), procedure.body());
    }

    @Test
    void fetching_another_tenants_patient_summaries_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        Session green = loginWithCsrf("admin@greenvalley.example.org");
        String greenPatientId = firstId(get(green.session, "/api/v1/patients").body());

        HttpResponse<String> cross = get(north.session, summariesBase(greenPatientId));
        assertEquals(404, cross.statusCode(), "another tenant's patient must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"CS-%s","fullName":"Clinical Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
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
