package com.healthcloud.document;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Secure patient-document API, nested under the patient it concerns (source-of-truth §19). Thin controller
 * (§31.5): tenant scoping, authorization (the object/relationship gate), storage, and validation live in
 * {@link PatientDocumentService}. Every route is authenticated and gated to the accessible patient.
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/documents")
public class PatientDocumentController {

    private final PatientDocumentService service;

    public PatientDocumentController(PatientDocumentService service) {
        this.service = service;
    }

    /** A patient's documents (metadata only), newest first. */
    @GetMapping
    public List<DocumentDto> list(@PathVariable UUID patientId) {
        return service.list(patientId);
    }

    /** Upload a document (multipart) for a patient. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDto> upload(
            @PathVariable UUID patientId, @RequestParam("file") MultipartFile file) {
        DocumentDto created = service.upload(patientId, file);
        return ResponseEntity
                .created(URI.create("/api/v1/patients/" + patientId + "/documents/" + created.id()))
                .body(created);
    }

    /** Download a document's bytes (re-authorized), streamed as an attachment. */
    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID patientId, @PathVariable UUID documentId) {
        DocumentContent content = service.download(patientId, documentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header("Content-Disposition", disposition.toString())
                .contentType(parseContentType(content.contentType()))
                .contentLength(content.bytes().length)
                .body(content.bytes());
    }

    private static MediaType parseContentType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
