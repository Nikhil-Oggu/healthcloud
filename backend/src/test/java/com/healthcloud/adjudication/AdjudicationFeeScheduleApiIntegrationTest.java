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
 * The fee schedule end to end in the adjudication engine (Phase 5 slice 9): under a plan that prices a procedure
 * below the billed charge, the covered line is allowed the fee-schedule amount (not the charge) and the plan/member
 * split is computed from it, while a procedure with no fee-schedule entry falls back to allowed = charge. A
 * dedicated plan (deductible met, 20% coinsurance) is created per test so the split is visible and the entries
 * never contaminate the shared seeded plans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AdjudicationFeeScheduleApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void a_priced_line_is_allowed_the_fee_schedule_amount_and_an_unpriced_line_falls_back_to_charge()
            throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        // A no-deductible, 20% plan so the coinsurance split off the allowed amount is visible.
        String planId = createPlan(admin);
        // Price 99213 at $120 allowed; leave 80053 unpriced (it will fall back to allowed = charge).
        assertEquals(201, post(admin, "/api/v1/coverage-plans/" + planId + "/fee-schedule",
                "{\"procedureCode\":\"99213\",\"allowedAmount\":120.00}").statusCode(), "price 99213 at $120");

        String patientId = firstId(createPatient(admin).body());
        enroll(admin, patientId, planId);

        // 99213 billed $200 (allowed $120 → write off $80); 80053 billed $50 (no entry → allowed $50).
        String claimId = acceptedClaim(admin, patientId, "2026-03-01", """
                [{"procedureCode":"99213","chargeAmount":200.00},
                 {"procedureCode":"80053","chargeAmount":50.00}]""");
        String body = adjudicate(admin, claimId).body();

        assertTrue(body.contains("\"outcome\":\"ADJUDICATED\""), body);
        // The priced line: charge $200 but allowed only $120; 20% of $120 = $24 member, $96 plan.
        assertTrue(body.contains("\"chargeAmount\":200.00"), "billed charge is recorded — " + body);
        assertTrue(body.contains("\"allowedAmount\":120.00"), "allowed is the fee-schedule amount — " + body);
        // The unpriced line falls back to allowed = charge ($50); 20% = $10 member, $40 plan.
        assertTrue(body.contains("\"allowedAmount\":50.00"), "unpriced line allowed = charge — " + body);
        // Totals: allowed $170 (not the $250 billed), plan $136, member $34.
        assertTrue(body.contains("\"totalChargeAmount\":250.00"), body);
        assertTrue(body.contains("\"totalAllowedAmount\":170.00"), "write-off excluded from allowed — " + body);
        assertTrue(body.contains("\"totalPlanPaidAmount\":136.00"), body);
        assertTrue(body.contains("\"totalMemberResponsibility\":34.00"), body);
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    /** A no-deductible, $0-copay, 20%-coinsurance plan so the fee-schedule split shows in the coinsurance. */
    private String createPlan(Session admin) throws Exception {
        HttpResponse<String> created = post(admin, "/api/v1/coverage-plans", """
                {"planCode":"AFS-%s","name":"Adjudication Fee Schedule Plan","planType":"PPO",
                 "deductibleAmount":0.00,"coinsuranceRate":0.2000,"copayAmount":0.00,"outOfPocketMax":6000.00}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"AFS-%s","fullName":"Fee Schedule Adj Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"AFS-M1\",\"effectiveFrom\":\"2020-01-01\"}"
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
