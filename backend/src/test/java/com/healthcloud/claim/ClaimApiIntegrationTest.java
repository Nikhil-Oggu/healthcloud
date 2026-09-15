package com.healthcloud.claim;

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
 * Claim API (Phase 4 slice 3) against a real embedded server with the seeded demo. Proves the resource is
 * authenticated and tenant-scoped, that a claim is created as an aggregate (header + lines) with a
 * backend-computed total, that each line's procedure is validated against the global catalog (unknown /
 * non-procedure → 400), that the object/relationship gate applies (a provider sees only assigned patients'
 * claims; another tenant is a secure 404), and — the §60 proof — that a CLAIMS_REVIEWER can list and read
 * claim-relevant data (codes + amounts) without any clinical narrative.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ClaimApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern CLAIM_NUMBER = Pattern.compile("\"claimNumber\":\"([^\"]+)\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String claimJson(String patientId) {
        return """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"99213","units":1,"chargeAmount":150.00},
                  {"procedureCode":"80053","units":1,"chargeAmount":45.50}]}""".formatted(patientId);
    }

    @Test
    void claims_require_authentication() throws Exception {
        HttpResponse<String> anon = get(null, "/api/v1/claims");
        assertEquals(401, anon.statusCode());
    }

    @Test
    void create_returns_the_aggregate_with_a_backend_computed_total() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        HttpResponse<String> created = post(coordinator, "/api/v1/claims", claimJson(patientId));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"status\":\"DRAFT\""), "a new claim is DRAFT");
        assertTrue(created.body().contains("\"claimNumber\":\"CLM-"), "a claim number is allocated");
        assertTrue(created.body().contains("\"procedureCode\":\"99213\""), "the office-visit line is present");
        assertTrue(created.body().contains("\"procedureCode\":\"80053\""), "the panel line is present");
        assertTrue(created.body().contains("\"totalChargeAmount\":195.5"),
                "the header total is the sum of the lines (150.00 + 45.50), computed on the backend");
    }

    @Test
    void a_single_read_returns_the_header_and_lines() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(post(coordinator, "/api/v1/claims", claimJson(patientId)).body());

        HttpResponse<String> read = get(coordinator.session, "/api/v1/claims/" + claimId);
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"procedureCode\":\"99213\""));
        assertTrue(read.body().contains("\"totalChargeAmount\":195.5"));
    }

    @Test
    void an_unknown_or_non_procedure_code_is_a_400() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        // A code that is not in the catalog at all.
        HttpResponse<String> unknown = post(coordinator, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"00000","chargeAmount":10.00}]}""".formatted(patientId));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertTrue(unknown.body().contains("VALIDATION_FAILED"));

        // A real code, but a DIAGNOSIS (ICD-10) — not billable as a procedure line.
        HttpResponse<String> diagnosis = post(coordinator, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"E11.9","chargeAmount":10.00}]}""".formatted(patientId));
        assertEquals(400, diagnosis.statusCode(), diagnosis.body());
    }

    @Test
    void a_reviewer_can_list_and_read_claim_data_without_clinical_context() throws Exception {
        // §60 proof: a claims reviewer reaches the coded, claim-relevant data (procedure codes + amounts). A
        // claim carries no clinical narrative at all, so there is nothing unrestricted for them to see here.
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String createdBody = post(coordinator, "/api/v1/claims", claimJson(patientId)).body();
        String claimNumber = claimNumber(createdBody);
        String claimId = firstId(createdBody);

        String reviewer = loginWithCsrf("reviewer@northcare.example.org").session;
        HttpResponse<String> list = get(reviewer, "/api/v1/claims?patientId=" + patientId);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains(claimNumber), "the reviewer's queue includes the claim");

        HttpResponse<String> read = get(reviewer, "/api/v1/claims/" + claimId);
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"procedureCode\":\"99213\""), "the reviewer sees the coded procedure");
        assertTrue(read.body().contains("\"totalChargeAmount\":195.5"), "the reviewer sees the amounts");
    }

    @Test
    void a_provider_sees_only_claims_for_assigned_patients() throws Exception {
        // A fresh patient the provider is NOT assigned to.
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String createdBody = post(coordinator, "/api/v1/claims", claimJson(patientId)).body();
        String claimNumber = claimNumber(createdBody);
        String claimId = firstId(createdBody);

        String provider = loginWithCsrf("provider@northcare.example.org").session;
        // The provider's queue must not include a claim for a patient they are not assigned to.
        HttpResponse<String> list = get(provider, "/api/v1/claims");
        assertFalse(list.body().contains(claimNumber), "an unassigned patient's claim is not in the provider's queue");
        // And a direct read of it is a secure 404 (no existence leak).
        assertEquals(404, get(provider, "/api/v1/claims/" + claimId).statusCode());
    }

    @Test
    void fetching_another_tenants_claim_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(north).body());
        String claimId = firstId(post(north, "/api/v1/claims", claimJson(patientId)).body());

        String green = loginWithCsrf("admin@greenvalley.example.org").session;
        HttpResponse<String> cross = get(green, "/api/v1/claims/" + claimId);
        assertEquals(404, cross.statusCode(), "another tenant's claim must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"CL-%s","fullName":"Claim Test Patient","dateOfBirth":"1990-01-01"}"""
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

    private static String claimNumber(String json) {
        Matcher m = CLAIM_NUMBER.matcher(json);
        assertTrue(m.find(), "expected a claimNumber in: " + json);
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
