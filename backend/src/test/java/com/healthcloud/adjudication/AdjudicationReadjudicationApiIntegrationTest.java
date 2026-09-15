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
 * Re-adjudication versioning (Phase 5 slice 11): re-running the engine on an already-ADJUDICATED claim writes a
 * new immutable version while every prior version is retained, backs out the prior version's benefit-accumulator
 * contribution so the deductible is not double-counted, and can flip a denied claim to covered once coverage is
 * added. Each test creates its own plan/patient so versions never contaminate the shared seeded data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AdjudicationReadjudicationApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void re_adjudicating_after_a_fee_schedule_change_writes_v2_and_retains_v1() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin, "0.00", "0.2000", "0.00"); // no deductible/copay, 20% coinsurance
        String patientId = firstId(createPatient(admin).body());
        enroll(admin, patientId, planId);

        // v1: no fee schedule → allowed = charge $200; 20% = $40 member, $160 plan.
        String claimId = acceptedClaim(admin, patientId, "2026-03-01",
                "[{\"procedureCode\":\"99213\",\"chargeAmount\":200.00}]");
        String v1 = adjudicate(admin, claimId).body();
        assertTrue(v1.contains("\"adjudicationVersion\":1"), v1);
        assertTrue(v1.contains("\"totalAllowedAmount\":200.00"), v1);
        assertTrue(v1.contains("\"totalMemberResponsibility\":40.00"), v1);

        // Price 99213 at $120, then re-adjudicate → v2: allowed $120; 20% = $24 member, $96 plan.
        assertEquals(201, post(admin, "/api/v1/coverage-plans/" + planId + "/fee-schedule",
                "{\"procedureCode\":\"99213\",\"allowedAmount\":120.00}").statusCode());
        String v2 = adjudicate(admin, claimId).body();
        assertTrue(v2.contains("\"adjudicationVersion\":2"), v2);
        assertTrue(v2.contains("\"totalAllowedAmount\":120.00"), "the fee schedule now applies — " + v2);
        assertTrue(v2.contains("\"totalMemberResponsibility\":24.00"), v2);

        // The current read is v2; the version history holds both, newest first.
        String current = get(admin.session, "/api/v1/claims/" + claimId + "/adjudication").body();
        assertTrue(current.contains("\"adjudicationVersion\":2"), "latest read is v2 — " + current);
        String versions = get(admin.session, "/api/v1/claims/" + claimId + "/adjudication/versions").body();
        assertEquals(2, countOccurrences(versions, "\"adjudicationVersion\":"), "both versions are listed");
        assertTrue(versions.indexOf("\"adjudicationVersion\":2") < versions.indexOf("\"adjudicationVersion\":1"),
                "newest first — " + versions);
    }

    @Test
    void re_adjudicating_unchanged_does_not_double_count_the_deductible() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin, "100.00", "0.2000", "0.00"); // $100 deductible, 20% coinsurance
        String patientId = firstId(createPatient(admin).body());
        enroll(admin, patientId, planId);

        // v1 on a $200 line: $100 deductible + 20% of the remaining $100 = $20 → member $120, plan $80.
        String claimId = acceptedClaim(admin, patientId, "2026-03-01",
                "[{\"procedureCode\":\"99213\",\"chargeAmount\":200.00}]");
        String v1 = adjudicate(admin, claimId).body();
        assertTrue(v1.contains("\"deductibleAppliedAmount\":100.00"), v1);
        assertTrue(v1.contains("\"totalMemberResponsibility\":120.00"), v1);
        assertTrue(v1.contains("\"totalPlanPaidAmount\":80.00"), v1);

        // Re-adjudicate with nothing changed: the prior contribution is reversed, so the full $100 deductible is
        // available again and v2 is IDENTICAL. (Without the reversal, the deductible would read as already met and
        // the amounts would differ — member $40, plan $160.)
        String v2 = adjudicate(admin, claimId).body();
        assertTrue(v2.contains("\"adjudicationVersion\":2"), v2);
        assertTrue(v2.contains("\"deductibleAppliedAmount\":100.00"), "deductible not double-counted — " + v2);
        assertTrue(v2.contains("\"totalMemberResponsibility\":120.00"), v2);
        assertTrue(v2.contains("\"totalPlanPaidAmount\":80.00"), v2);
    }

    @Test
    void a_denied_claim_can_be_re_adjudicated_as_covered_after_enrollment() throws Exception {
        Session admin = loginWithCsrf("admin@northcare.example.org");
        String planId = createPlan(admin, "0.00", "0.2000", "0.00");
        String patientId = firstId(createPatient(admin).body()); // not enrolled yet

        String claimId = acceptedClaim(admin, patientId, "2026-03-01",
                "[{\"procedureCode\":\"99213\",\"chargeAmount\":200.00}]");
        String v1 = adjudicate(admin, claimId).body();
        assertTrue(v1.contains("\"outcome\":\"DENIED_NO_ELIGIBILITY\""), "no coverage yet — " + v1);

        // Enroll retroactively (covering the service date), then re-adjudicate → v2 is covered.
        enroll(admin, patientId, planId);
        String v2 = adjudicate(admin, claimId).body();
        assertTrue(v2.contains("\"adjudicationVersion\":2"), v2);
        assertTrue(v2.contains("\"outcome\":\"ADJUDICATED\""), "now covered — " + v2);
        assertTrue(v2.contains("\"totalMemberResponsibility\":40.00"), v2);
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String createPlan(Session admin, String deductible, String coinsurance, String copay) throws Exception {
        HttpResponse<String> created = post(admin, "/api/v1/coverage-plans", """
                {"planCode":"REA-%s","name":"Re-adjudication Plan","planType":"PPO",
                 "deductibleAmount":%s,"coinsuranceRate":%s,"copayAmount":%s,"outOfPocketMax":6000.00}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), deductible, coinsurance, copay));
        assertEquals(201, created.statusCode(), created.body());
        return firstId(created.body());
    }

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"REA-%s","fullName":"Re-adjudication Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"REA-M1\",\"effectiveFrom\":\"2020-01-01\"}"
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) {
            count++;
            i += needle.length();
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
