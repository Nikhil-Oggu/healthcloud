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
 * The benefit accumulator (Phase 5 slice 2) end to end: the annual deductible carries across claims within a
 * benefit year (a later claim sees less deductible remaining, so the plan pays more), and a different benefit
 * year is a separate accumulator (the deductible resets). Uses the PPO ($1,500 deductible / 20% coinsurance /
 * $25 copay).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AdjudicationAccumulatorApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    // Select the SEEDED "Standard PPO" by its unique plan code (…-PPO-STD), not "the first PPO in the list".
    // These tests share one database + tenant with the other adjudication tests, several of which create their
    // own PPO-type plans (e.g. "Adjudication Fee Schedule Plan", code AFS-…). The plan list is ordered by plan
    // code ascending, so an AFS-… plan sorts before …-PPO-STD and a "first PPO" match would grab the wrong plan
    // (its fee-scheduled 99213 breaks these exact-amount assertions). Matching the plan code is order-independent.
    private static final Pattern PPO_ID =
            Pattern.compile("\\{\"id\":\"([0-9a-fA-F-]{36})\",\"planCode\":\"[^\"]*PPO-STD\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void the_deductible_carries_across_claims_then_resets_next_year() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, ppoPlanId(coordinator));

        // Claim 1 (2026): $1,000 charge. Fresh $1,500 deductible → after the $25 copay the whole $975 is
        // deductible, coinsurance 0 → the plan pays $0. Deductible met so far: $975.
        String c1 = acceptedClaim(coordinator, reviewer, patientId, "2026-03-01", "1000.00");
        String a1 = adjudicate(reviewer, c1).body();
        assertTrue(a1.contains("\"outcome\":\"ADJUDICATED\""), a1);
        assertTrue(a1.contains("\"totalPlanPaidAmount\":0.00"), "first claim: all deductible, plan pays 0 — " + a1);

        // Claim 2 (2026): $1,000 charge. Only $525 of the deductible remains ($1,500 − $975). After the $25
        // copay: $525 to the deductible, then 20% of the remaining $450 = $90 → member $640, plan $360.
        String c2 = acceptedClaim(coordinator, reviewer, patientId, "2026-04-01", "1000.00");
        String a2 = adjudicate(reviewer, c2).body();
        assertTrue(a2.contains("\"deductibleAppliedAmount\":525.00"), "carried-over deductible remaining — " + a2);
        assertTrue(a2.contains("\"totalPlanPaidAmount\":360.00"), "second claim: the plan now pays — " + a2);

        // Claim 3 (2025): a different benefit year → a separate accumulator, so the deductible is fresh again
        // and a $1,000 claim is again all deductible → the plan pays $0.
        String c3 = acceptedClaim(coordinator, reviewer, patientId, "2025-06-01", "1000.00");
        String a3 = adjudicate(reviewer, c3).body();
        assertTrue(a3.contains("\"totalPlanPaidAmount\":0.00"), "a new benefit year resets the deductible — " + a3);
    }

    @Test
    void the_out_of_pocket_max_caps_the_member_then_the_plan_pays_everything() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, ppoPlanId(coordinator)); // PPO: $1,500 deductible, 20%, $25 copay, $6,000 OOP

        // A $40,000 claim: copay $25 + deductible $1,500 + coinsurance 20% of $38,475 = $7,695 → gross member
        // $9,220, but the $6,000 OOP max caps it → member $6,000, $3,220 shifts to the plan, plan pays $34,000.
        String c1 = acceptedClaim(coordinator, reviewer, patientId, "2026-05-01", "40000.00");
        String a1 = adjudicate(reviewer, c1).body();
        assertTrue(a1.contains("\"totalMemberResponsibility\":6000.00"), "member capped at the OOP max — " + a1);
        assertTrue(a1.contains("\"totalPlanPaidAmount\":34000.00"), "the plan absorbs the excess — " + a1);
        assertTrue(a1.contains("\"oopMaxAppliedAmount\":3220.00"), "the line records the OOP shift — " + a1);

        // A second same-year claim: the OOP max is already met → the member pays $0 and the plan pays 100%.
        String c2 = acceptedClaim(coordinator, reviewer, patientId, "2026-06-01", "500.00");
        String a2 = adjudicate(reviewer, c2).body();
        assertTrue(a2.contains("\"totalMemberResponsibility\":0.00"), "OOP met → member owes nothing — " + a2);
        assertTrue(a2.contains("\"totalPlanPaidAmount\":500.00"), "the plan pays the whole charge — " + a2);
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"ACC-%s","fullName":"Accumulator Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    /** Drive a one-line claim to ACCEPTED: coordinator creates + submits (v0), reviewer accepts (v1). */
    private String acceptedClaim(Session coordinator, Session reviewer, String patientId, String serviceDate,
                                 String charge) throws Exception {
        String claimId = firstId(post(coordinator, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"%s","lines":[
                  {"procedureCode":"99213","chargeAmount":%s}]}""".formatted(patientId, serviceDate, charge))
                .body());
        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0).statusCode());
        assertEquals(200, patchStatus(reviewer, claimId, "ACCEPTED", 1).statusCode());
        return claimId;
    }

    private String ppoPlanId(Session s) throws Exception {
        Matcher m = PPO_ID.matcher(get(s.session, "/api/v1/coverage-plans").body());
        assertTrue(m.find(), "expected a PPO plan in the seeded catalog");
        return m.group(1);
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"ACC-M1\",\"effectiveFrom\":\"2020-01-01\"}"
                        .formatted(planId));
        assertEquals(201, enrolled.statusCode(), enrolled.body());
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
