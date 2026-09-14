package com.healthcloud.document;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientAccessGuard;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Secure patient documents (source-of-truth §19). Uploads and downloads are authorized on the backend and
 * every path routes through {@link PatientAccessGuard}, so document access inherits the patient
 * object/relationship gate (§21 layer 6): a provider reaches only documents for patients they are assigned to,
 * a PATIENT only their own, cross-tenant is a secure 404.
 *
 * <p>Bytes live in {@link DocumentStorage} (a local-filesystem stand-in for private S3); this table holds only
 * metadata. The bytes never enter a DTO, log, or event (§23.4) — a download re-authorizes and streams them.
 * The malware scan defaults to CLEAN this slice; the scanner + quarantine download gate are the next slice.
 */
@Service
@Transactional(readOnly = true)
public class PatientDocumentService {

    /**
     * Roles allowed to upload. Staff (CARE_COORDINATOR/ORG_ADMIN) may upload for any patient in the tenant, and
     * a PATIENT for their OWN record (the access guard enforces own-record-only). Providers/reviewers cannot
     * upload — mirroring the consent write rule. Reads (list/download) are open to any same-tenant user who can
     * reach the patient through the guard.
     */
    private static final String[] WRITE_ROLES = {"PATIENT", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** Content types we accept for a synthetic document store — a small, safe allowlist. */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/gif", "text/plain", "text/csv");

    private final PatientDocumentRepository documents;
    private final DocumentStorage storage;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;
    private final long maxSizeBytes;

    public PatientDocumentService(PatientDocumentRepository documents, DocumentStorage storage,
                                  PatientAccessGuard accessGuard, UserContextAccessor userContext,
                                  @Value("${healthcloud.documents.max-size-bytes}") long maxSizeBytes) {
        this.documents = documents;
        this.storage = storage;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
        this.maxSizeBytes = maxSizeBytes;
    }

    /** A patient's documents (metadata only), newest first. Requires access to the patient (else secure 404). */
    public List<DocumentDto> list(UUID patientId) {
        UUID organizationId = accessGuard.requireAccessibleInTenant(patientId).getOrganizationId();
        return documents.findByOrganizationIdAndPatientIdOrderByUploadedAtDesc(organizationId, patientId)
                .stream().map(DocumentDto::from).toList();
    }

    /**
     * Upload a document for a patient: store the bytes, then write the metadata row. Requires a write role and
     * access to the patient (a PATIENT only their own record, else secure 404). The file must be present, within
     * the size limit, and of an allowed content type (else 400).
     */
    @Transactional
    public DocumentDto upload(UUID patientId, MultipartFile file) {
        userContext.requireAnyRole(WRITE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = accessGuard.requireAccessibleInTenant(patientId).getOrganizationId();

        validate(file);
        byte[] content = readBytes(file);
        String storageKey = storage.store(organizationId, patientId, content);

        PatientDocument saved = documents.save(new PatientDocument(
                organizationId, patientId, sanitizedFileName(file), resolveContentType(file),
                content.length, storageKey, DocumentScanStatus.CLEAN, caller.userId()));
        return DocumentDto.from(saved);
    }

    /**
     * The authorized bytes of a document for download. Requires access to the patient (else secure 404); the
     * document must belong to that patient (else 404). Bytes are loaded from storage only after the check.
     */
    public DocumentContent download(UUID patientId, UUID documentId) {
        UUID organizationId = accessGuard.requireAccessibleInTenant(patientId).getOrganizationId();
        PatientDocument document = documents.findByIdAndOrganizationId(documentId, organizationId)
                .filter(d -> d.getPatientId().equals(patientId))
                .orElseThrow(NotFoundException::new);
        byte[] bytes = storage.load(document.getStorageKey());
        return new DocumentContent(document.getFileName(), document.getContentType(), bytes);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A non-empty file is required.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The file exceeds the maximum allowed size.");
        }
        String contentType = resolveContentType(file);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "That file type is not allowed.");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new DocumentStorageException("Failed to read the uploaded file", e);
        }
    }

    /** The declared content type, lower-cased and stripped of any parameters (e.g. "; charset=…"). */
    private static String resolveContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase();
    }

    /**
     * A safe display filename: keep only the final path segment (defeat any directory parts a client might
     * send) and fall back to a generic name. The stored bytes are addressed by a generated key, never this
     * name, so this is purely for display/download.
     */
    private static String sanitizedFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "document";
        }
        String base = original.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String name = slash >= 0 ? base.substring(slash + 1) : base;
        name = name.trim();
        if (name.isEmpty()) {
            return "document";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
