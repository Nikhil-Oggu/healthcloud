-- V5: Patient profile — the first care-coordination (Phase 2) business entity.
-- SYNTHETIC data only. A patient is tenant-owned: it belongs to exactly one organization
-- (source-of-truth §32.9). Access is tenant-scoped in this slice; object-relationship,
-- consent/purpose and field-level masking arrive in later Phase 2 / Phase 3 slices.

CREATE TABLE patient (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID         NOT NULL REFERENCES organization (id),   -- tenant key
    medical_record_number VARCHAR(32)  NOT NULL,                                -- synthetic MRN
    full_name             VARCHAR(200) NOT NULL,
    date_of_birth         DATE         NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT patient_status_chk CHECK (status IN ('ACTIVE', 'INACTIVE')),
    -- MRN is unique within a tenant, not globally.
    CONSTRAINT ux_patient_org_mrn UNIQUE (organization_id, medical_record_number),
    -- Lets future tenant-owned rows (e.g. service_request) reference patient with a COMPOSITE FK
    -- that includes organization_id, so a bug cannot cross tenants (source-of-truth §32.10).
    CONSTRAINT ux_patient_id_org  UNIQUE (id, organization_id)
);

-- Candidate index from §32.11: tenant-scoped listing/filtering by status.
CREATE INDEX ix_patient_org_status ON patient (organization_id, status);
