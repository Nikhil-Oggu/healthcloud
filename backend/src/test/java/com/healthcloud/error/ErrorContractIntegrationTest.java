package com.healthcloud.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Verifies the global error contract and correlation id against a REAL embedded server, so the
 * responses produced inside the Spring Security filter chain (401 entry point) and the correlation
 * filter genuinely run. Uses the JDK HttpClient, matching the project's session-flow test style.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class ErrorContractIntegrationTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void unauthenticated_request_returns_error_shape_and_correlation_id() throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, res.statusCode());
        assertTrue(res.body().contains("\"code\":\"UNAUTHENTICATED\""), res.body());
        assertTrue(res.body().contains("\"correlationId\":"), res.body());
        // Every response carries a correlation id header, even a 401 from inside the security chain.
        assertNotNull(res.headers().firstValue(CorrelationId.HEADER).orElse(null));
    }

    @Test
    void inbound_correlation_id_is_reused_in_header_and_body() throws Exception {
        String cid = "test-cid-123";
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me"))
                        .header(CorrelationId.HEADER, cid)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, res.statusCode());
        assertEquals(cid, res.headers().firstValue(CorrelationId.HEADER).orElse(null));
        assertTrue(res.body().contains("\"correlationId\":\"" + cid + "\""), res.body());
    }

    @Test
    void unsafe_inbound_correlation_id_is_replaced() throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me"))
                        .header(CorrelationId.HEADER, "not a valid id!!")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String returned = res.headers().firstValue(CorrelationId.HEADER).orElse("");
        assertFalse(returned.contains(" "), "unsafe value must not be echoed back");
        assertFalse(returned.isEmpty(), "a safe correlation id should still be generated");
    }

    @Test
    void missing_required_parameter_returns_validation_error() throws Exception {
        // dev-login requires the "email" form parameter; omitting it must yield the standard shape.
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("\"code\":\"VALIDATION_FAILED\""), res.body());
        assertTrue(res.body().contains("email"), res.body());
    }
}
