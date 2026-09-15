-- V15: Clinical summaries — limited clinical context per patient (source-of-truth §Phase 4). SYNTHETIC data.
-- A clinical summary is a short clinical note about a patient's encounter, pointing at a diagnosis code from
-- the global medical_code catalog (V14). It is the first Phase-4 resource that is ABOUT a patient, so it is
-- tenant-owned (organization_id) and inherits the patient object/relationship gate (§21 layer 6, via
-- PatientAccessGuard) exactly like patient_document.
--
-- Consent + field masking (§22.5/§23): the free-text `narrative` is the sensitive, consent-controlled field
-- (CLINICAL_CONTEXT). The structured, claim-relevant diagnosis CODE stays visible, so a caller can see what
-- was diagnosed/billed without unrestricted medical context — the Phase-4 §60 proof begins here.

CREATE TABLE clinical_summary (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID         NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id            UUID         NOT NULL,
    summary_type          VARCHAR(20)  NOT NULL,
    encounter_date        DATE         NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    diagnosis_code_system VARCHAR(16)  NOT NULL,
    diagnosis_code        VARCHAR(16)  NOT NULL,
    narrative             VARCHAR(4000),                                        -- consent-controlled (§23)
    author_user_id        UUID         NOT NULL REFERENCES app_user (id),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lock_version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT clinical_summary_type_chk
        CHECK (summary_type IN ('ENCOUNTER', 'DIAGNOSIS', 'TREATMENT', 'LAB_RESULT')),
    -- Composite FK including organization_id: a summary cannot attach to another tenant's patient (§32.10).
    CONSTRAINT fk_clinical_summary_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    -- Structural integrity: the diagnosis must be a real catalog code. medical_code is global (not
    -- tenant-owned), so this FK carries no organization_id — it targets the catalog's (code_system, code)
    -- unique key. The service additionally requires an ICD-10-CM (diagnosis) code and returns a clean 400.
    CONSTRAINT fk_clinical_summary_diagnosis FOREIGN KEY (diagnosis_code_system, diagnosis_code)
        REFERENCES medical_code (code_system, code)
);

-- The common read: a patient's clinical summaries within the tenant (newest encounter first).
CREATE INDEX ix_clinical_summary_org_patient ON clinical_summary (organization_id, patient_id);
