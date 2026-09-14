package com.healthcloud.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase 3 slice 14 — secure documents (§19). Uploads/downloads are authorized on the backend and inherit the
 * patient object/relationship gate (§21 layer 6, via {@code PatientAccessGuard}): staff and a patient on their
 * OWN record may upload; an assigned provider (or the patient) may download; an unassigned provider is a secure
 * 404; another tenant's document is a secure 404. The local-filesystem storage stand-in is pointed at
 * {@code target/} so the test leaves nothing behind a {@code clean}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
@TestPropertySource(properties = "healthcloud.documents.dir=target/test-documents")
class PatientDocumentApiIntegrationTest {

    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base(String patientId) {
        return "/api/v1/patients/" + patientId + "/documents";
    }

    @Test
    void staff_upload_list_and_download_round_trips_the_bytes() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "Sam Sample");
        byte[] bytes = "synthetic care summary\n".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> uploaded = upload(coordinator, base(samId), "summary.txt", "text/plain", bytes);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        assertTrue(uploaded.body().contains("\"fileName\":\"summary.txt\""));
        assertTrue(uploaded.body().contains("\"scanStatus\":\"CLEAN\""));
        String documentId = firstId(uploaded.body());

        HttpResponse<String> listed = get(coordinator.session, base(samId));
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains(documentId), "the uploaded document should be listed");

        HttpResponse<byte[]> downloaded = getBytes(coordinator.session, base(samId) + "/" + documentId + "/content");
        assertEquals(200, downloaded.statusCode());
        assertArrayEquals(bytes, downloaded.body(), "download returns the exact uploaded bytes");
    }

    @Test
    void a_patient_manages_documents_on_their_own_record_but_not_another() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String fernId = patientId(coordinator, "Fern Fixture");

        Session patient = loginWithCsrf("patient@northcare.example.org"); // linked to Sam Sample
        String samId = patientId(patient, "Sam Sample");
        byte[] bytes = "my own upload".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> own = upload(patient, base(samId), "mine.txt", "text/plain", bytes);
        assertEquals(201, own.statusCode(), own.body());
        String documentId = firstId(own.body());
        HttpResponse<byte[]> downloaded = getBytes(patient.session, base(samId) + "/" + documentId + "/content");
        assertArrayEquals(bytes, downloaded.body());

        // Another patient's documents are a secure 404 for both write and read.
        assertEquals(404, upload(patient, base(fernId), "x.txt", "text/plain", bytes).statusCode());
        assertEquals(404, get(patient.session, base(fernId)).statusCode());
    }

    @Test
    void an_unassigned_provider_cannot_read_documents() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String mockId = patientId(coordinator, "Mock Muller"); // provider Dana is NOT assigned to Mock
        HttpResponse<String> uploaded =
                upload(coordinator, base(mockId), "note.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8));
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        String documentId = firstId(uploaded.body());

        Session provider = loginWithCsrf("provider@northcare.example.org");
        assertEquals(404, get(provider.session, base(mockId)).statusCode(), "list is a secure 404");
        assertEquals(404, getBytes(provider.session, base(mockId) + "/" + documentId + "/content").statusCode(),
                "download is a secure 404");
    }

    @Test
    void a_read_only_role_cannot_upload() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "Sam Sample");

        Session reviewer = loginWithCsrf("reviewer@northcare.example.org");
        HttpResponse<String> denied =
                upload(reviewer, base(samId), "x.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(403, denied.statusCode(), "a reviewer is not a document write role");
        assertTrue(denied.body().contains("ACCESS_DENIED"));
    }

    @Test
    void another_tenants_document_is_a_secure_404() throws Exception {
        Session northcare = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(northcare, "Sam Sample");
        String documentId = firstId(
                upload(northcare, base(samId), "n.txt", "text/plain", "n".getBytes(StandardCharsets.UTF_8)).body());

        Session greenvalley = loginWithCsrf("coordinator@greenvalley.example.org");
        assertEquals(404, get(greenvalley.session, base(samId)).statusCode(), "cross-tenant list → 404");
        assertEquals(404, getBytes(greenvalley.session, base(samId) + "/" + documentId + "/content").statusCode(),
                "cross-tenant download → 404");
    }

    @Test
    void a_disallowed_content_type_is_rejected() throws Exception {
        Session coordinator = loginWithCsrf("coordinator@northcare.example.org");
        String samId = patientId(coordinator, "Sam Sample");

        HttpResponse<String> denied =
                upload(coordinator, base(samId), "archive.zip", "application/zip", "PK".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, denied.statusCode(), denied.body());
        assertTrue(denied.body().contains("VALIDATION_FAILED"));
    }

    // --- helpers -------------------------------------------------------------

    private record Session(String session, String xsrf) {}

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String patientId(Session s, String fullName) throws Exception {
        String body = get(s.session, "/api/v1/patients").body();
        Matcher m = Pattern.compile(
                "\\{\"id\":\"([0-9a-fA-F-]{36})\"[^}]*\"fullName\":\"" + Pattern.quote(fullName) + "\"")
                .matcher(body);
        assertTrue(m.find(), "expected a patient named " + fullName + " in: " + body);
        return m.group(1);
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

    /** POST a single-part multipart/form-data body under field name "file". */
    private HttpResponse<String> upload(Session s, String path, String fileName, String contentType, byte[] content)
            throws Exception {
        String boundary = "----hcBoundary" + java.util.UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        body.write(preamble.getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", "SESSION=" + s.session + "; XSRF-TOKEN=" + s.xsrf)
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String session, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path)).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String session, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path)).header("Cookie", "SESSION=" + session).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
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
