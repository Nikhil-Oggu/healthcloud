package com.healthcloud.patient;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 3 slice 3 — field-level masking on the patient read (§23). {@code dateOfBirth} is consent-controlled
 * (DEMOGRAPHICS_CONTACT / CARE_COORDINATION): deny-by-default, so it is masked until an applicable consent
 * GRANT exists, and a more-specific provider DENY re-masks it for that provider. Write responses are never
 * masked (the caller supplied the value). Non-controlled fields (name/MRN/status) stay visible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PatientFieldMaskingApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");
    private static final String DOB = "1990-01-01";

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String consentBase(String patientId) {
        return "/api/v1/patients/" + patientId + "/consent-directives";
    }

    private static final String DEMOGRAPHICS_GRANT = """
            {"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"DEMOGRAPHICS_CONTACT",
             "scopeType":"ORGANIZATION"}""";

    @Test
    void date_of_birth_is_masked_without_consent_but_the_write_response_is_not() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");

        // The create (write) response is unmasked — the caller just supplied the DOB.
        HttpResponse<String> created = createPatient(coordinator, "MK-" + suffix());
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains(DOB), "the write response returns the DOB the caller supplied");
        assertTrue(created.body().contains("\"maskedFields\":[]"), "writes are not masked");
        String id = firstId(created.body());

        // The read is field-safe: no consent yet → DOB masked (deny-by-default).
        HttpResponse<String> read = get(coordinator.session, "/api/v1/patients/" + id);
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"dateOfBirth\":null"), "DOB is masked without consent");
        assertTrue(read.body().contains("\"maskedFields\":[\"dateOfBirth\"]"));
        assertFalse(read.body().contains(DOB), "the masked value must not appear anywhere in the response");
        assertTrue(read.body().contains("\"status\":"), "non-controlled fields stay visible");
    }

    @Test
    void an_organization_grant_reveals_date_of_birth() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String id = firstId(createPatient(coordinator, "MK-" + suffix()).body());

        assertEquals(201, post(coordinator, consentBase(id), DEMOGRAPHICS_GRANT).statusCode());

        HttpResponse<String> read = get(coordinator.session, "/api/v1/patients/" + id);
        assertTrue(read.body().contains(DOB), "an applicable DEMOGRAPHICS_CONTACT grant reveals the DOB");
        assertTrue(read.body().contains("\"maskedFields\":[]"));
    }

    @Test
    void the_list_masks_date_of_birth_without_consent() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String mrn = "MK-" + suffix();
        // A DOB unique to this patient (other tests/seed data use different dates) so the assertion below
        // is about THIS no-consent patient, not contaminated by other patients that were granted consent.
        String uniqueDob = "1967-07-07";
        assertEquals(201, createPatient(coordinator, mrn, uniqueDob).statusCode());

        HttpResponse<String> list = get(coordinator.session, "/api/v1/patients");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains(mrn), "the created patient is in the list");
        assertFalse(list.body().contains(uniqueDob), "this patient's DOB is masked in the list (no consent)");
        assertTrue(list.body().contains("\"maskedFields\":[\"dateOfBirth\"]"));
    }

    @Test
    void a_provider_specific_deny_re_masks_despite_an_org_grant() throws Exception {
        String providerId = meId(loginWithCsrf("provider@northcare.example.org"));
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String id = firstId(createPatient(coordinator, "MK-" + suffix()).body());

        // Org-wide grant → both the coordinator and the provider can see the DOB.
        assertEquals(201, post(coordinator, consentBase(id), DEMOGRAPHICS_GRANT).statusCode());
        Session provider = loginWithCsrf("provider@northcare.example.org");
        assertTrue(get(provider.session, "/api/v1/patients/" + id).body().contains(DOB));

        // A provider-specific DENY re-masks it for THAT provider (most-specific wins)...
        assertEquals(201, post(coordinator, consentBase(id), """
                {"effect":"DENY","purpose":"CARE_COORDINATION","dataCategory":"DEMOGRAPHICS_CONTACT",
                 "scopeType":"PROVIDER","scopeRefId":"%s"}""".formatted(providerId)).statusCode());
        assertFalse(get(provider.session, "/api/v1/patients/" + id).body().contains(DOB),
                "the specifically-denied provider no longer sees the DOB");

        // ...but the coordinator (not the named provider) still sees it via the org grant.
        assertTrue(get(coordinator.session, "/api/v1/patients/" + id).body().contains(DOB),
                "another actor still sees the DOB under the org-wide grant");
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private String suffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private HttpResponse<String> createPatient(Session s, String mrn) throws Exception {
        return createPatient(s, mrn, DOB);
    }

    private HttpResponse<String> createPatient(Session s, String mrn, String dob) throws Exception {
        return post(s, "/api/v1/patients", """
                {"medicalRecordNumber":"%s","fullName":"Masking Test Patient","dateOfBirth":"%s"}"""
                .formatted(mrn, dob));
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

    private String meId(Session s) throws Exception {
        Matcher m = USER_ID.matcher(get(s.session, "/api/v1/me").body());
        assertTrue(m.find());
        return m.group(1);
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
