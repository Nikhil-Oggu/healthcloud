package com.healthcloud.observability;

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
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 11 slice 1 — the Prometheus metrics endpoint. Proves {@code /actuator/prometheus} is exposed but
 * still requires authentication (we did not loosen the security boundary — only health/info are public),
 * that it emits Micrometer's auto-instrumented metrics, and that a <b>committed</b> adjudication increments
 * the custom domain counter {@code healthcloud_adjudications_total}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
// Spring Boot disables metrics exporters (incl. the Prometheus scrape endpoint) in tests by default, to avoid
// polluting a shared registry. @AutoConfigureMetrics re-enables them so this test exercises the real endpoint.
@AutoConfigureMetrics
class MetricsEndpointIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern SEEDED_PPO_ID =
            Pattern.compile("\\{\"id\":\"([0-9a-fA-F-]{36})\",\"planCode\":\"[^\"]*PPO-STD\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void prometheus_endpoint_requires_authentication() throws Exception {
        HttpResponse<String> anon = get(null, "/actuator/prometheus");
        assertTrue(anon.statusCode() == 401 || anon.statusCode() == 403,
                "prometheus must not be public (only health/info are); got " + anon.statusCode());
    }

    @Test
    void prometheus_endpoint_emits_auto_metrics_when_authenticated() throws Exception {
        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        HttpResponse<String> metrics = get(reviewer.session, "/actuator/prometheus");
        assertEquals(200, metrics.statusCode(), metrics.body());
        // Micrometer auto-instruments the JVM + the HTTP server + the DB pool out of the box.
        assertTrue(metrics.body().contains("jvm_memory_used_bytes"), "expected JVM metrics");
        assertTrue(metrics.body().contains("application=\"healthcloud\""), "expected the common application tag");
    }

    @Test
    void a_committed_adjudication_increments_the_domain_counter() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String patientId = firstId(createPatient(coordinator).body());
        enroll(coordinator, patientId, seededPpoPlanId(coordinator));

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        String claimId = acceptedClaim(coordinator, reviewer, patientId);
        HttpResponse<String> adjudicated = adjudicate(reviewer, claimId);
        assertEquals(200, adjudicated.statusCode(), adjudicated.body());

        HttpResponse<String> metrics = get(reviewer.session, "/actuator/prometheus");
        assertEquals(200, metrics.statusCode(), metrics.body());
        // The counter is named `healthcloud.adjudications`; the Prometheus registry renders it with a _total
        // suffix and our outcome/type tags. An ADJUDICATED, first-time adjudication increments this series.
        assertTrue(metrics.body().contains("healthcloud_adjudications_total"),
                "expected the custom adjudication counter in the output");
        assertTrue(metrics.body().contains("outcome=\"ADJUDICATED\""), "expected the outcome tag");
        assertTrue(metrics.body().contains("type=\"initial\""), "expected the type tag");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private HttpResponse<String> createPatient(Session s) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"MET-%s","fullName":"Metrics Test Patient","dateOfBirth":"1990-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    }

    private HttpResponse<String> createClaim(Session s, String patientId) throws Exception {
        return post(s, "/api/v1/claims", """
                {"patientId":"%s","serviceDate":"2026-01-10","lines":[
                  {"procedureCode":"99213","units":1,"chargeAmount":150.00},
                  {"procedureCode":"80053","units":1,"chargeAmount":45.50}]}""".formatted(patientId));
    }

    private String acceptedClaim(Session coordinator, Session reviewer, String patientId) throws Exception {
        String claimId = firstId(createClaim(coordinator, patientId).body());
        assertEquals(200, patchStatus(coordinator, claimId, "SUBMITTED", 0).statusCode());
        assertEquals(200, patchStatus(reviewer, claimId, "ACCEPTED", 1).statusCode());
        return claimId;
    }

    private String seededPpoPlanId(Session s) throws Exception {
        Matcher m = SEEDED_PPO_ID.matcher(get(s.session, "/api/v1/coverage-plans").body());
        assertTrue(m.find(), "expected the seeded Standard PPO in the catalog");
        return m.group(1);
    }

    private void enroll(Session s, String patientId, String planId) throws Exception {
        HttpResponse<String> enrolled = post(s, "/api/v1/patients/" + patientId + "/eligibility",
                "{\"coveragePlanId\":\"%s\",\"memberId\":\"MET-M1\",\"effectiveFrom\":\"2020-01-01\"}"
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
