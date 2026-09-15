package com.healthcloud.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Medical code catalog API (Phase 4 slice 1) against a real embedded server with the seeded catalog. Proves:
 * the catalog requires authentication; search filters by system and free-text term; a single code resolves;
 * an unknown code is a 404 and an unknown system a 400. The catalog is global (not tenant-scoped), so any
 * authenticated user in either tenant reads the same codes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class MedicalCodeApiIntegrationTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void catalog_requires_authentication() throws Exception {
        HttpResponse<String> anon = get(null, "/api/v1/medical-codes?system=ICD10CM");
        assertEquals(401, anon.statusCode());
    }

    @Test
    void search_filters_by_system_and_term() throws Exception {
        String session = login("provider@northcare.example.org");

        // A description term scoped to ICD-10 finds the seeded diabetes diagnosis and no procedure codes.
        HttpResponse<String> diab = get(session, "/api/v1/medical-codes?system=ICD10CM&q=diabetes");
        assertEquals(200, diab.statusCode());
        assertTrue(diab.body().contains("E11.9"), "should find the seeded diabetes diagnosis");
        assertFalse(diab.body().contains("99213"), "an ICD-10 search must not return CPT codes");

        // A code-prefix term against CPT finds the office-visit code.
        HttpResponse<String> visit = get(session, "/api/v1/medical-codes?system=CPT&q=992");
        assertEquals(200, visit.statusCode());
        assertTrue(visit.body().contains("99213"), "should find the seeded office-visit CPT code");
    }

    @Test
    void single_code_lookup_hit_and_miss() throws Exception {
        String session = login("reviewer@northcare.example.org");

        HttpResponse<String> hit = get(session, "/api/v1/medical-codes/ICD10CM/E11.9");
        assertEquals(200, hit.statusCode());
        assertTrue(hit.body().contains("Type 2 diabetes"), "the lookup returns the code's description");

        HttpResponse<String> miss = get(session, "/api/v1/medical-codes/ICD10CM/NOPE.0");
        assertEquals(404, miss.statusCode(), "an unknown code is a 404");
        assertTrue(miss.body().contains("NOT_FOUND"));
    }

    @Test
    void an_unknown_system_is_a_400() throws Exception {
        String session = login("reviewer@northcare.example.org");
        HttpResponse<String> bad = get(session, "/api/v1/medical-codes?system=BOGUS");
        assertEquals(400, bad.statusCode(), "an unknown code system is a validation error, not a 500");
        assertTrue(bad.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void the_catalog_is_global_across_tenants() throws Exception {
        // Both tenants' users see the same reference codes — it is not tenant-owned data.
        String north = get(login("provider@northcare.example.org"),
                "/api/v1/medical-codes/ICD10CM/I10").body();
        String green = get(login("admin@greenvalley.example.org"),
                "/api/v1/medical-codes/ICD10CM/I10").body();
        assertTrue(north.contains("hypertension") && green.contains("hypertension"),
                "the same global code resolves for either tenant");
    }

    // --- helpers -------------------------------------------------------------

    private String login(String email) throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/api/v1/dev-login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("email=" + email))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), "dev-login should succeed for " + email);
        String session = cookie(login.headers().allValues("Set-Cookie"), "SESSION");
        assertNotNull(session, "dev-login should set a SESSION cookie");
        return session;
    }

    private HttpResponse<String> get(String session, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (session != null) {
            b.header("Cookie", "SESSION=" + session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
