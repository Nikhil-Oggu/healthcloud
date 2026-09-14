-- V13: Patient documents — secure document metadata (source-of-truth §19). SYNTHETIC data only.
-- The design is "private object store for the BYTES + PostgreSQL for the METADATA": this table holds only
-- metadata (never the file contents). The bytes live behind a DocumentStorage abstraction — a local-filesystem
-- stand-in now, swapped for private S3 at the cloud phase — addressed by the opaque storage_key.
--
-- Every document belongs to a patient, so access inherits the patient object/relationship gate (§21 layer 6,
-- via PatientAccessGuard): a provider reaches only documents for patients they are assigned to, a patient only
-- their own, cross-tenant is a secure 404. scan_status carries the malware-scan lifecycle (§19): PENDING until
-- scanned, then CLEAN or QUARANTINED — the scanner + download quarantine gate are the NEXT slice; this slice
-- records CLEAN.

CREATE TABLE patient_document (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID         NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id            UUID         NOT NULL,
    file_name             VARCHAR(255) NOT NULL,
    content_type          VARCHAR(100) NOT NULL,
    size_bytes            BIGINT       NOT NULL,
    storage_key           VARCHAR(512) NOT NULL,                                -- opaque key into the blob store
    scan_status           VARCHAR(20)  NOT NULL,
    uploaded_by_user_id   UUID         NOT NULL REFERENCES app_user (id),
    uploaded_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lock_version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT patient_document_size_chk CHECK (size_bytes >= 0),
    CONSTRAINT patient_document_scan_status_chk CHECK (scan_status IN ('PENDING','CLEAN','QUARANTINED')),
    -- Composite FK including organization_id: a document cannot attach to another tenant's patient (§32.10).
    CONSTRAINT fk_patient_document_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id)
);

-- The common read: a patient's documents within the tenant (newest first).
CREATE INDEX ix_patient_document_org_patient ON patient_document (organization_id, patient_id);

-- Each stored blob is addressed by a single metadata row.
CREATE UNIQUE INDEX ux_patient_document_storage_key ON patient_document (storage_key);
