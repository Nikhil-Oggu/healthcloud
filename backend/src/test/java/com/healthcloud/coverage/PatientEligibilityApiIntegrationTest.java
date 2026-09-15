package com.healthcloud.coverage;

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
 * Patient eligibility API (Phase 4 slice 6) against a real embedded server with the seeded demo. Proves the
 * resource is authenticated and patient-gated, that only staff may enroll, that the plan and period are
 * validated (unknown plan → 400, overlap → 409), that {@code asOf} filters to the covering period, that a
 * patient reads their own coverage, and that another tenant's patient is a secure 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PatientEligibilityApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String eligibilityBase(String patientId) {
        return "/api/v1/patients/" + patientId + "/eligibility";
    }

    private String enrollJson(String planId, String from, String to) {
        return to == null
                ? "{\"coveragePlanId\":\"%s\",\"memberId\":\"MBR-1\",\"effectiveFrom\":\"%s\"}"
                        .formatted(planId, from)
                : "{\"coveragePlanId\":\"%s\",\"memberId\":\"MBR-1\",\"effectiveFrom\":\"%s\",\"effectiveTo\":\"%s\"}"
                        .formatted(planId, from, to);
    }

    @Test
    void eligibility_requires_authentication() throws Exception {
        HttpResponse<String> anon = get(null, "/api/v1/patients/" + UUID.randomUUID() + "/eligibility");
        assertEquals(401, anon.statusCode());
    }

    @Test
    void a_coordinator_enrolls_and_reads_it_back() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstPlanId(coordinator);

        HttpResponse<String> enrolled = post(coordinator, eligibilityBase(patientId),
                enrollJson(planId, "2026-01-01", null));
        assertEquals(201, enrolled.statusCode(), enrolled.body());
        assertTrue(enrolled.body().contains("\"coveragePlanName\":"), "the response carries the plan name");
        assertTrue(enrolled.body().contains("MBR-1"));

        HttpResponse<String> list = get(coordinator.session, eligibilityBase(patientId));
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("MBR-1"), "the enrollment is listed");
    }

    @Test
    void a_provider_cannot_enroll() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstPlanId(coordinator);

        Session provider = loginWithCsrf("provider@northcare.example.org");
        HttpResponse<String> enrolled = post(provider, eligibilityBase(patientId),
                enrollJson(planId, "2026-01-01", null));
        assertEquals(403, enrolled.statusCode());
        assertTrue(enrolled.body().contains("ACCESS_DENIED"));
    }

    @Test
    void a_patient_reads_their_own_coverage() throws Exception {
        // The seeder enrolls Sam Sample (the patient@ login) in the PPO — the patient reads it via self-service.
        Session patient = loginWithCsrf("patient@northcare.example.org");
        String ownId = firstId(get(patient.session, "/api/v1/patients").body());

        HttpResponse<String> own = get(patient.session, eligibilityBase(ownId));
        assertEquals(200, own.statusCode());
        assertTrue(own.body().contains("NC-M0001"), "the patient sees their seeded member id");
    }

    @Test
    void an_unknown_plan_is_a_400() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        HttpResponse<String> bad = post(coordinator, eligibilityBase(patientId),
                enrollJson(UUID.randomUUID().toString(), "2026-01-01", null));
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void an_overlapping_period_is_a_409() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstPlanId(coordinator);

        assertEquals(201, post(coordinator, eligibilityBase(patientId),
                enrollJson(planId, "2026-01-01", "2026-12-31")).statusCode());
        // A second period that overlaps the first.
        HttpResponse<String> overlap = post(coordinator, eligibilityBase(patientId),
                enrollJson(planId, "2026-06-01", "2027-06-01"));
        assertEquals(409, overlap.statusCode(), overlap.body());
        assertTrue(overlap.body().contains("CONFLICT"));
    }

    @Test
    void as_of_filters_to_the_covering_period() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String planId = firstPlanId(coordinator);
        assertEquals(201, post(coordinator, eligibilityBase(patientId),
                enrollJson(planId, "2025-01-01", "2025-12-31")).statusCode());

        assertTrue(get(coordinator.session, eligibilityBase(patientId) + "?asOf=2025-06-15").body()
                .contains("MBR-1"), "a date inside the period returns it");
        assertFalse(get(coordinator.session, eligibilityBase(patientId) + "?asOf=2024-01-01").body()
                .contains("MBR-1"), "a date before the period returns nothing");
    }

    @Test
    void fetching_another_tenants_patient_eligibility_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        String northPatientId = firstId(createPatient(north).body());

        Session green = loginWithCsrf("admin@greenvalley.example.org");
        HttpResponse<String> cross = get(green.session, eligibilityBase(northPatientId));
        assertEquals(404, cross.statusCode(), "another tenant's patient must be a secure 404");
        assertTrue(cross.body().contains("NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"EL-%s","fullName":"Eligibility Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    /** The first coverage plan id in the caller's tenant (a seeded plan). */
    private String firstPlanId(Session s) throws Exception {
        return firstId(get(s.session, "/api/v1/coverage-plans").body());
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
