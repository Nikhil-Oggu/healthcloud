package com.healthcloud.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full authentication session lifecycle against a REAL embedded server (so Spring Session and its
 * SESSION cookie genuinely operate): dev-login -> /me (authenticated) -> logout -> /me (401).
 * Also exercises the CSRF cookie/header handshake for the state-changing logout request.
 * Uses the JDK HttpClient to stay independent of Spring Boot's test-client module layout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AuthenticationSessionIntegrationTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void login_me_logout_lifecycle() throws Exception {
        // 1. dev-login (CSRF-exempt) -> establishes a SESSION cookie
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("email=provider@northcare.example.org"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        String session = cookie(login.headers().allValues("Set-Cookie"), "SESSION");
        assertNotNull(session, "dev-login should set a SESSION cookie");

        // 2. /me with the session -> authenticated, correct tenant; also yields the XSRF cookie
        HttpResponse<String> me = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me"))
                        .header("Cookie", "SESSION=" + session)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("NorthCare Health"));
        String xsrf = cookie(me.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
        assertNotNull(xsrf, "an XSRF-TOKEN cookie should be issued");

        // 3. logout (state-changing) with SESSION + CSRF cookie and X-XSRF-TOKEN header
        HttpResponse<String> logout = http.send(
                HttpRequest.newBuilder(uri("/api/v1/logout"))
                        .header("Cookie", "SESSION=" + session + "; XSRF-TOKEN=" + xsrf)
                        .header("X-XSRF-TOKEN", xsrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logout.statusCode());

        // 4. the old session is no longer authenticated
        HttpResponse<String> after = http.send(
                HttpRequest.newBuilder(uri("/api/v1/me"))
                        .header("Cookie", "SESSION=" + session)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, after.statusCode());
    }

    /** Extracts a cookie value by name from a list of Set-Cookie headers. */
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
