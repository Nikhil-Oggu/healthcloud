package com.healthcloud.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 11 slice 4 — health & readiness probes. Proves the Kubernetes-style probes are exposed and public
 * (SecurityConfig permits {@code /actuator/health/**}) and that the liveness/readiness/root split behaves:
 * <ul>
 *   <li>{@code /actuator/health/liveness} → 200 UP (process alive — the container health check target);</li>
 *   <li>{@code /actuator/health/readiness} → 200 UP with the DB in the readiness group (traffic gating);</li>
 *   <li>{@code /actuator/health} → 200 UP exposing the {@code db} and custom {@code outbox} components.</li>
 * </ul>
 * Under the {@code local} test profile the outbox relay is disabled, so the custom indicator reports UP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class HealthProbesIntegrationTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void liveness_probe_is_public_and_up() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");
        assertEquals(200, response.statusCode(), "liveness must be public and reachable pre-auth");
        assertTrue(response.body().contains("\"status\":\"UP\""), "liveness should be UP: " + response.body());
    }

    @Test
    void readiness_probe_is_public_up_and_includes_the_database() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");
        assertEquals(200, response.statusCode(), "readiness must be public and reachable pre-auth");
        assertTrue(response.body().contains("\"status\":\"UP\""), "readiness should be UP: " + response.body());
    }

    @Test
    void root_health_exposes_db_and_the_custom_outbox_component() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"status\":\"UP\""), "root health should aggregate to UP: " + body);
        assertTrue(body.contains("\"db\""), "root health should show the db component: " + body);
        assertTrue(body.contains("\"outbox\""), "root health should show the custom outbox component: " + body);
    }
}
