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
 * Plan exclusions end to end in the adjudication engine (Phase 5 slice 4): under a plan that excludes a
 * procedure, a claim line for that procedure adjudicates NOT_COVERED (member owes the charge, plan pays 0) while
 * the other lines are COVERED, and the excluded charge does not consume the deductible. A dedicated plan is
 * created per test so the exclusion never contaminates the shared seeded plans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AdjudicationExclusionApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void an_excluded_line_is_not_covered_and_does_not_touch_the_deductible() throws Exception {
        // One ORG_ADMIN can drive the whole flow (plan admin + enroll + claim submit + accept + adjudicate).
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin);
        assertEquals(201, post(admin, "/api/v1/coverage-plans/" + planId + "/exclusions",
                "{\"procedureCode\":\"80053\"}").statusCode(), "exclude 80053 on the plan");

        String patientId = firstId(createPatient(admin).body());
        enroll(admin, patientId, planId);

        // Claim 1: a covered $150 line (99213) + an excluded $45.50 line (80053).
        String c1 = acceptedClaim(admin, patientId, "2026-03-01", """
                [{"procedureCode":"99213","chargeAmount":150.00},
                 {"procedureCode":"80053","chargeAmount":45.50}]""");
        String a1 = adjudicate(admin, c1).body();
        assertTrue(a1.contains("\"outcome\":\"ADJUDICATED\""), a1);
        assertTrue(a1.contains("\"outcome\":\"COVERED\""), "the 99213 line is covered — " + a1);
        assertTrue(a1.contains("\"outcome\":\"NOT_COVERED\""), "the 80053 line is not covered — " + a1);
        // Only the covered line is allowed; the member owes the covered $150 (all deductible) + excluded $45.50.
        assertTrue(a1.contains("\"totalAllowedAmount\":150.00"), "excluded charge is not allowed — " + a1);
        assertTrue(a1.contains("\"totalMemberResponsibility\":195.50"), "member owes covered + excluded — " + a1);
        assertTrue(a1.contains("\"totalPlanPaidAmount\":0.00"), a1);

        // Claim 2 (same year): only $125 of the deductible was consumed by claim 1 (the covered 99213 after its
        // $25 copay) — the excluded $45.50 did NOT count. So on a $2,000 line: $25 copay, $1,375 deductible,
        // 20% of the remaining $600 = $120 → member $1,520, plan $480. (If the exclusion had wrongly hit the
        // deductible, the plan-paid amount would differ.)
        String c2 = acceptedClaim(admin, patientId, "2026-04-01",
                "[{\"procedureCode\":\"99213\",\"chargeAmount\":2000.00}]");
        String a2 = adjudicate(admin, c2).body();
        assertTrue(a2.contains("\"deductibleAppliedAmount\":1375.00"), "only the covered line consumed it — " + a2);
        assertTrue(a2.contains("\"totalPlanPaidAmount\":480.00"), "the exclusion did not touch the deductible — " + a2);
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String createPlan(Session admin) throws Exception {
        HttpResponse<String> created = post(admin, "/api/v1/coverage-plans", """
                {"planCode":"AEX-%s","name":"Adjudication Exclusion Plan","planType":"PPO",
                 "deductibleAmount":1500.00,"coinsuranceRate":0.2000,"copayAmount":25.00,"outOfPocketMax":6000.00}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"AEX-%s","fullName":"Exclusion Adj Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"AEX-M1\",\"effectiveFrom\":\"2020-01-01\"}"
                        .formatted(planId));
        assertEquals(201, enrolled.statusCode(), enrolled.body());
    }

    /** Create a claim (JSON lines array), submit (v0) and accept (v1) — all as the same broad-role caller. */
    private String acceptedClaim(Session s, String patientId, String serviceDate, String linesJson)
            throws Exception {
        String claimId = firstId(post(s, "/api/v1/claims",
                "{\"patientId\":\"%s\",\"serviceDate\":\"%s\",\"lines\":%s}"
                        .formatted(patientId, serviceDate, linesJson)).body());
        assertEquals(200, patchStatus(s, claimId, "SUBMITTED", 0).statusCode());
        assertEquals(200, patchStatus(s, claimId, "ACCEPTED", 1).statusCode());
        return claimId;
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
