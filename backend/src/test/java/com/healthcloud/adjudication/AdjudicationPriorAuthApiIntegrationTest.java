package com.healthcloud.adjudication;

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
 * Prior-authorization requirements end to end in the adjudication engine (Phase 6 slice 2): under a plan that
 * requires prior auth for a procedure, a claim line for it adjudicates AUTH_REQUIRED (member owes the charge,
 * plan pays 0, deductible untouched) unless an APPROVED authorization covers the service date. Approving a
 * covering authorization and re-adjudicating (slice 11) flips the line to COVERED; an authorization outside the
 * service window does not apply. A dedicated plan is created per test so requirements never contaminate the
 * shared seeded plans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AdjudicationPriorAuthApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void a_required_procedure_without_authorization_is_auth_required() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        requirePriorAuth(admin, planId, "99213");
        String patientId = firstId(createPatient(admin).body());
        enroll(admin, patientId, planId);

        // Claim 1: a single 99213 line that requires prior auth, with no authorization → AUTH_REQUIRED.
        String c1 = acceptedClaim(admin, patientId, "2026-03-01",
                "[{\"procedureCode\":\"99213\",\"chargeAmount\":150.00}]");
        String a1 = adjudicate(admin, c1).body();
        assertTrue(a1.contains("\"outcome\":\"ADJUDICATED\""), a1);
        assertTrue(a1.contains("\"outcome\":\"AUTH_REQUIRED\""), "the required line has no auth — " + a1);
        assertTrue(a1.contains("\"totalAllowedAmount\":0.00"), "nothing is allowed — " + a1);
        assertTrue(a1.contains("\"totalMemberResponsibility\":150.00"), "member owes the charge — " + a1);
        assertTrue(a1.contains("\"totalPlanPaidAmount\":0.00"), a1);

        // Claim 2 (same year): a covered $2,000 line (80053, not required) proves the AUTH_REQUIRED line did NOT
        // consume the deductible — the full $1,500 is available: $25 copay, $1,500 deductible, 20% of $475 = $95
        // → member $1,620, plan $380.
        String c2 = acceptedClaim(admin, patientId, "2026-04-01",
                "[{\"procedureCode\":\"80053\",\"chargeAmount\":2000.00}]");
        String a2 = adjudicate(admin, c2).body();
        assertTrue(a2.contains("\"deductibleAppliedAmount\":1500.00"), "auth-required line left the deductible — " + a2);
        assertTrue(a2.contains("\"totalPlanPaidAmount\":380.00"), a2);
    }

    @Test
    void approving_a_covering_authorization_flips_the_line_to_covered() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        requirePriorAuth(admin, planId, "99213");
        String patientId = firstId(createPatient(admin).body());
        enroll(admin, patientId, planId);

        String claimId = acceptedClaim(admin, patientId, "2026-03-01",
                "[{\"procedureCode\":\"99213\",\"chargeAmount\":150.00}]");
        assertTrue(adjudicate(admin, claimId).body().contains("\"outcome\":\"AUTH_REQUIRED\""),
                "with no authorization the line is AUTH_REQUIRED");

        // Request + approve an authorization for 99213 whose window covers the 2026-03-01 service date.
        String authId = requestAuth(admin, patientId, planId, "99213", "2026-01-01", "2026-12-31");
        assertEquals(200, patchAuthStatus(admin, authId, "APPROVED", 0).statusCode());

        // Re-adjudicate (slice 11): a new version, now COVERED (allowed 150, copay 25, deductible 125, member 150).
        String re = adjudicate(admin, claimId).body();
        assertTrue(re.contains("\"adjudicationVersion\":2"), "re-adjudication writes v2 — " + re);
        assertTrue(re.contains("\"outcome\":\"COVERED\""), "the authorized line is now covered — " + re);
        assertTrue(re.contains("\"totalAllowedAmount\":150.00"), re);
    }

    @Test
    void an_authorization_outside_the_service_window_does_not_apply() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        requirePriorAuth(admin, planId, "99213");
        String patientId = firstId(createPatient(admin).body());
        enroll(admin, patientId, planId);

        // An APPROVED authorization, but its window (June) does not cover the March service date.
        String authId = requestAuth(admin, patientId, planId, "99213", "2026-06-01", "2026-06-30");
        assertEquals(200, patchAuthStatus(admin, authId, "APPROVED", 0).statusCode());

        String claimId = acceptedClaim(admin, patientId, "2026-03-01",
                "[{\"procedureCode\":\"99213\",\"chargeAmount\":150.00}]");
        String a = adjudicate(admin, claimId).body();
        assertTrue(a.contains("\"outcome\":\"AUTH_REQUIRED\""),
                "an authorization outside the service window does not apply — " + a);
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String createPlan(Session admin) throws Exception {
        HttpResponse<String> created = post(admin, "/api/v1/coverage-plans", """
                {"planCode":"APA-%s","name":"Adjudication Prior Auth Plan","planType":"PPO",
                 "deductibleAmount":1500.00,"coinsuranceRate":0.2000,"copayAmount":25.00,"outOfPocketMax":6000.00}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    private void requirePriorAuth(Session admin, String planId, String code) throws Exception {
        HttpResponse<String> req = post(admin, "/api/v1/coverage-plans/" + planId + "/prior-auth-requirements",
                "{\"procedureCode\":\"%s\"}".formatted(code));
        assertEquals(201, req.statusCode(), req.body());
    }

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"APA-%s","fullName":"Prior Auth Adj Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"APA-M1\",\"effectiveFrom\":\"2020-01-01\"}"
                        .formatted(planId));
        assertEquals(201, enrolled.statusCode(), enrolled.body());
    }

    private String requestAuth(Session s, String patientId, String planId, String code, String from, String to)
            throws Exception {
        HttpResponse<String> created = post(s, "/api/v1/prior-authorizations", """
                {"patientId":"%s","coveragePlanId":"%s","procedureCode":"%s",
                 "requestedServiceFrom":"%s","requestedServiceTo":"%s"}"""
                .formatted(patientId, planId, code, from, to));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    private HttpResponse<String> patchAuthStatus(Session s, String authId, String target, long expectedVersion)
            throws Exception {
        String json = "{\"targetStatus\":\"%s\",\"expectedVersion\":%d}".formatted(target, expectedVersion);
        return http.send(
                HttpRequest.newBuilder(uri("/api/v1/prior-authorizations/" + authId + "/status"))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Create a claim (JSON lines array), submit (v0) and accept (v1) — all as the same broad-role caller. */
    private String acceptedClaim(Session s, String patientId, String serviceDate, String linesJson)
            throws Exception {
        String claimId = firstId(post(s, "/api/v1/claims",
                "{\"patientId\":\"%s\",\"serviceDate\":\"%s\",\"lines\":%s}"
                        .formatted(patientId, serviceDate, linesJson)).body());
        assertEquals(200, patchClaimStatus(s, claimId, "SUBMITTED", 0).statusCode());
        assertEquals(200, patchClaimStatus(s, claimId, "ACCEPTED", 1).statusCode());
        return claimId;
    }

    private HttpResponse<String> patchClaimStatus(Session s, String claimId, String target, long expectedVersion)
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
