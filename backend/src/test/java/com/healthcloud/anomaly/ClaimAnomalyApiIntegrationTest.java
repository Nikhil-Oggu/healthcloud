package com.healthcloud.anomaly;

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
 * Claim anomaly API (Phase 6 slice 11) against a real embedded server with the seeded demo. Proves the resource
 * is authenticated and tenant-scoped, that a reviewer's scan raises the deterministic signals (a same-date,
 * shared-procedure sibling → DUPLICATE_CLAIM; a total over the threshold → HIGH_TOTAL_CHARGE), that a rescan is
 * idempotent, that scanning is the reviewer's action (a coordinator → 403 despite reaching the claim), and that
 * the object/relationship gate applies (another tenant's claim is a secure 404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ClaimAnomalyApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern CLAIM_NUMBER = Pattern.compile("\"claimNumber\":\"([^\"]+)\"");
    private static final Pattern SIGNAL_TYPE = Pattern.compile("\"signalType\":\"([A-Z_]+)\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String claimJson(String patientId, String serviceDate, String procedureCode, String charge) {
        return """
                {"patientId":"%s","serviceDate":"%s",\
                "lines":[{"procedureCode":"%s","units":1,"chargeAmount":%s}]}"""
                .formatted(patientId, serviceDate, procedureCode, charge);
    }

    @Test
    void anomalies_require_authentication() throws Exception {
        assertEquals(401, get(null, "/api/v1/claims/" + UUID.randomUUID() + "/anomalies").statusCode());
    }

    @Test
    void scan_flags_a_duplicate_claim() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());

        // Two claims for the same patient, same service date, same procedure — the second duplicates the first.
        String firstBody = post(coordinator, "/api/v1/claims", claimJson(patientId, "2025-11-01", "99213", "150.00"))
                .body();
        String firstNumber = claimNumber(firstBody);
        String secondId = firstId(post(coordinator, "/api/v1/claims",
                claimJson(patientId, "2025-11-01", "99213", "150.00")).body());

        HttpResponse<String> scan = postNoBody(reviewer, "/api/v1/claims/" + secondId + "/anomaly-scan");
        assertEquals(200, scan.statusCode(), scan.body());
        assertTrue(scan.body().contains("DUPLICATE_CLAIM"), "a duplicate is flagged: " + scan.body());
        assertTrue(scan.body().contains(firstNumber), "the detail names the duplicated claim number");

        // The signal is persisted and readable.
        HttpResponse<String> list = get(reviewer.session, "/api/v1/claims/" + secondId + "/anomalies");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("DUPLICATE_CLAIM"));
    }

    @Test
    void rescanning_is_idempotent() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        post(coordinator, "/api/v1/claims", claimJson(patientId, "2025-11-01", "99213", "150.00"));
        String secondId = firstId(post(coordinator, "/api/v1/claims",
                claimJson(patientId, "2025-11-01", "99213", "150.00")).body());

        int firstScan = countSignals(postNoBody(reviewer, "/api/v1/claims/" + secondId + "/anomaly-scan").body());
        int secondScan = countSignals(postNoBody(reviewer, "/api/v1/claims/" + secondId + "/anomaly-scan").body());
        assertEquals(firstScan, secondScan, "a rescan replaces, not appends — the count is stable");
        assertTrue(firstScan >= 1, "the duplicate is detected");
    }

    @Test
    void scan_flags_a_high_total_charge() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        // A lone claim (no duplicate) whose total exceeds the 5000 threshold.
        String claimId = firstId(post(coordinator, "/api/v1/claims",
                claimJson(patientId, "2025-11-01", "99213", "6000.00")).body());

        HttpResponse<String> scan = postNoBody(reviewer, "/api/v1/claims/" + claimId + "/anomaly-scan");
        assertEquals(200, scan.statusCode(), scan.body());
        assertTrue(scan.body().contains("HIGH_TOTAL_CHARGE"), "the high total is flagged: " + scan.body());
    }

    @Test
    void a_non_reviewer_cannot_scan() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        String claimId = firstId(post(coordinator, "/api/v1/claims",
                claimJson(patientId, "2025-11-01", "99213", "150.00")).body());

        // The coordinator can reach the claim (broad role) but scanning is the reviewer's action → 403.
        assertEquals(403, postNoBody(coordinator, "/api/v1/claims/" + claimId + "/anomaly-scan").statusCode());
    }

    @Test
    void scanning_another_tenants_claim_is_a_secure_404() throws Exception {
        Session north = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(north).body());
        String claimId = firstId(post(north, "/api/v1/claims",
                claimJson(patientId, "2025-11-01", "99213", "150.00")).body());

        // Green Valley's admin holds a scan role but cannot reach NorthCare's claim → a secure 404 (no leak).
        Session green = loginWithCsrf("admin@greenvalley.example.org");
        assertEquals(404, postNoBody(green, "/api/v1/claims/" + claimId + "/anomaly-scan").statusCode());
        assertEquals(404, get(green.session, "/api/v1/claims/" + claimId + "/anomalies").statusCode());
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"ANM-%s","fullName":"Anomaly Test Patient","dateOfBirth":"1990-01-01"}"""
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

    private HttpResponse<String> postNoBody(Session s, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
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

    private static int countSignals(String json) {
        Matcher m = SIGNAL_TYPE.matcher(json);
        int count = 0;
        while (m.find()) {
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
