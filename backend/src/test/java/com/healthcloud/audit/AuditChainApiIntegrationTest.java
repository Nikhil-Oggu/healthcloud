package com.healthcloud.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The tamper-evident audit chain (Phase 7 slice 2) against a real embedded server + real Postgres. Proves an
 * intact chain verifies as valid; that a field modified directly in the database, and a row deleted directly from
 * it, are BOTH detected (with the break located); that tampering with one org's chain does not flip another's
 * (per-org keys); and that verification is gated to AUDITOR/ORG_ADMIN. Destructive cases restore the row afterward
 * so the shared chain is left intact for other tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AuditChainApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void verification_requires_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/audit-events/verify").statusCode());
    }

    @Test
    void an_intact_chain_verifies_as_valid() throws Exception {
        String claimId = adjudicatedClaim();  // appends a CLAIM_ADJUDICATED event to NorthCare's chain

        Session auditor = loginWithCsrf("auditor@northcare.example.org");
        HttpResponse<String> verify = get(auditor.session, "/api/v1/audit-events/verify");
        assertEquals(200, verify.statusCode(), verify.body());
        assertTrue(verify.body().contains("\"valid\":true"), verify.body());
        assertTrue(verify.body().contains("\"brokenAtSequence\":null"), verify.body());
        assertNotNull(claimId);
    }

    @Test
    void modifying_a_field_in_the_database_is_detected_then_restored() throws Exception {
        String claimId = adjudicatedClaim();
        UUID eventId = eventIdForClaim(claimId);
        String originalDetail = jdbc.queryForObject(
                "SELECT detail FROM audit_event WHERE id = ?", String.class, eventId);

        // Tamper: rewrite the detail directly in the DB (bypassing the append path, so no re-fingerprinting).
        jdbc.update("UPDATE audit_event SET detail = 'TAMPERED — plan paid nothing' WHERE id = ?", eventId);
        assertTrue(verifyBody("auditor@northcare.example.org").contains("\"valid\":false"),
                "a modified field must break the chain");

        // Restore the original detail → the recomputed fingerprint matches again → chain valid.
        jdbc.update("UPDATE audit_event SET detail = ? WHERE id = ?", originalDetail, eventId);
        assertTrue(verifyBody("auditor@northcare.example.org").contains("\"valid\":true"),
                "restoring the field restores the chain");
    }

    @Test
    void deleting_a_row_from_the_database_is_detected_then_restored() throws Exception {
        // Two events so the first is a genuine middle row; deleting it breaks the link for the row after it.
        String firstClaim = adjudicatedClaim();
        adjudicatedClaim();
        UUID firstEventId = eventIdForClaim(firstClaim);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM audit_event WHERE id = ?", firstEventId);
        jdbc.update("DELETE FROM audit_event WHERE id = ?", firstEventId);
        assertTrue(verifyBody("auditor@northcare.example.org").contains("\"valid\":false"),
                "a deleted row must break the chain");

        // Restore the exact row (same fingerprints) → chain valid again.
        jdbc.update("""
                INSERT INTO audit_event
                  (id, organization_id, occurred_at, actor_user_id, action, resource_type, resource_id,
                   outcome, correlation_id, detail, sequence_no, prev_hash, entry_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                row.get("id"), row.get("organization_id"), row.get("occurred_at"), row.get("actor_user_id"),
                row.get("action"), row.get("resource_type"), row.get("resource_id"), row.get("outcome"),
                row.get("correlation_id"), row.get("detail"), row.get("sequence_no"), row.get("prev_hash"),
                row.get("entry_hash"));
        assertTrue(verifyBody("auditor@northcare.example.org").contains("\"valid\":true"),
                "restoring the row restores the chain");
    }

    @Test
    void tampering_with_one_org_does_not_flip_another() throws Exception {
        // NorthCare gets an event; Green Valley gets one too (independent chains, per-org keys).
        String northClaim = adjudicatedClaim();
        adjudicatedClaimInGreenValley();
        UUID northEventId = eventIdForClaim(northClaim);
        String originalDetail = jdbc.queryForObject(
                "SELECT detail FROM audit_event WHERE id = ?", String.class, northEventId);

        jdbc.update("UPDATE audit_event SET detail = 'TAMPERED' WHERE id = ?", northEventId);
        assertTrue(verifyBody("auditor@northcare.example.org").contains("\"valid\":false"),
                "NorthCare's chain is broken");
        assertTrue(verifyBody("admin@greenvalley.example.org").contains("\"valid\":true"),
                "Green Valley's independent chain is unaffected");

        jdbc.update("UPDATE audit_event SET detail = ? WHERE id = ?", originalDetail, northEventId);
        assertTrue(verifyBody("auditor@northcare.example.org").contains("\"valid\":true"));
    }

    @Test
    void an_admin_may_verify_but_a_reviewer_and_patient_cannot() throws Exception {
        assertEquals(200, get(loginSession("admin@northcare.example.org"), "/api/v1/audit-events/verify").statusCode());
        assertEquals(200, get(loginSession("auditor@northcare.example.org"), "/api/v1/audit-events/verify").statusCode());
        assertEquals(403, get(loginSession("reviewer@northcare.example.org"), "/api/v1/audit-events/verify").statusCode());
        assertEquals(403, get(loginSession("patient@northcare.example.org"), "/api/v1/audit-events/verify").statusCode());
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String verifyBody(String email) throws Exception {
        HttpResponse<String> verify = get(loginWithCsrf(email).session, "/api/v1/audit-events/verify");
        assertEquals(200, verify.statusCode(), verify.body());
        return verify.body();
    }

    /** The audit event id recording a claim's adjudication (there is exactly one per adjudication). */
    private UUID eventIdForClaim(String claimId) {
        return jdbc.queryForObject(
                "SELECT id FROM audit_event WHERE resource_type = 'CLAIM' AND resource_id = ? "
                        + "AND action = 'CLAIM_ADJUDICATED' ORDER BY sequence_no DESC LIMIT 1",
                UUID.class, UUID.fromString(claimId));
    }

    /** Create + enroll + accept + adjudicate a claim in NorthCare; returns the claim id. */
    private String adjudicatedClaim() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());
        return claimId;
    }

    /** Same, but in Green Valley (its own tenant chain). */
    private void adjudicatedClaimInGreenValley() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@greenvalley.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, firstPlanId(coordinator));
        Session reviewer = loginWithCsrf("reviewer@greenvalley.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        assertEquals(200, adjudicate(reviewer, claimId).statusCode());
    }

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"AUC-%s","fullName":"Audit Chain Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

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
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"AUC-M1\",\"effectiveFrom\":\"2020-01-01\"}"
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
