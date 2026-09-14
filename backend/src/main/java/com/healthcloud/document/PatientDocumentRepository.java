package com.healthcloud.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Patient documents, tenant-safe by design: every finder is scoped by {@code organizationId}, so a caller can
 * only reach metadata in their own organization. There is deliberately no bare {@code findById} for business
 * code (§32: load by {@code (organizationId, id)} so another tenant's row is simply not found → secure 404).
 */
public interface PatientDocumentRepository extends JpaRepository<PatientDocument, UUID> {

    /** Load a document only if it belongs to the tenant; otherwise empty (→ secure 404). */
    Optional<PatientDocument> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A patient's documents within the tenant, newest first. */
    List<PatientDocument> findByOrganizationIdAndPatientIdOrderByUploadedAtDesc(
            UUID organizationId, UUID patientId);
}
