package com.healthcloud.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import com.healthcloud.devdata.DevDataSeeder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Auth-hardening slice (Phase 10): the deployed app runs the {@code demo} profile (synthetic seed so a
 * Cognito login maps to a real app user) but NOT {@code local}, so the unauthenticated {@code /dev-login}
 * bypass must be completely closed. This test boots under {@code demo} (the deploy shape) and proves:
 * <ul>
 *   <li>{@link DevDataSeeder} is still present (the seed the Cognito demo relies on runs); and</li>
 *   <li>{@link DevLoginController} is NOT registered (the bean is {@code @Profile("local")} only); and</li>
 *   <li>{@code POST /api/v1/dev-login} is rejected with 401 (SecurityConfig only permits it under
 *       {@code local}) — so no one can trade an email for an authenticated ORG_ADMIN session.</li>
 * </ul>
 * Kafka is disabled via properties because the test-only {@code application-local.yml} (which normally
 * stops the relay/consumers) loads only under the {@code local} profile, which this test deliberately omits.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "healthcloud.outbox.relay.enabled=false",
            "healthcloud.kafka.consumers.enabled=false"
        })
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("demo")
class DeployProfileNoDevLoginTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void demo_profile_seeds_but_has_no_dev_login_controller() {
        assertEquals(1, context.getBeanNamesForType(DevDataSeeder.class).length,
                "the demo profile must still seed synthetic data for Cognito logins");
        assertEquals(0, context.getBeanNamesForType(DevLoginController.class).length,
                "dev-login must not be wired outside the local profile");
    }

    @Test
    void dev_login_endpoint_is_not_permitted_off_local() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/dev-login?email=admin@northcare.example.org"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // The bypass is hard-denied off `local`: no permitAll and no CSRF exemption, so the token-less POST
        // is rejected by CSRF (403) before it could ever reach the (absent) controller. 401 (auth required)
        // is equally acceptable — the only unacceptable outcome is a 200 that would mint a session.
        assertTrue(response.statusCode() == 401 || response.statusCode() == 403,
                "the dev-login bypass must be denied on the deployed (demo) profile, was " + response.statusCode());
        assertFalse(response.statusCode() == 200, "dev-login must never establish a session off local");
        // No SESSION cookie is issued — nothing was authenticated.
        assertFalse(response.headers().allValues("set-cookie").stream().anyMatch(c -> c.startsWith("SESSION=")),
                "no authenticated session cookie should be set");
    }

    @Test
    void prometheus_endpoint_requires_auth_off_local() throws Exception {
        // Phase 11 slice 2 permits /actuator/prometheus WITHOUT a session only under the `local` profile (so a
        // local scraper works). On the deployed `demo` profile it must stay authenticated — an anonymous scrape
        // is denied. (health/info remain the only public actuator paths.)
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertTrue(response.statusCode() == 401 || response.statusCode() == 403,
                "prometheus must require auth off local, was " + response.statusCode());
    }
}
