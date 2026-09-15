-- V14: Medical code catalog — the shared clinical/claims vocabulary that opens Phase 4.
-- ICD-10-CM diagnoses and HCPCS/CPT procedures. Later slices attach these codes to clinical
-- summaries (diagnoses) and claim lines (procedures).
--
-- DELIBERATELY GLOBAL, NOT tenant-owned: these are public national code standards, identical for
-- every tenant, so this table has NO organization_id and no PatientAccessGuard/consent gate (unlike
-- every business table since Phase 2). It is the app's first shared business-reference table — the
-- same shape as `role`. Codes are read-only reference data, so there is no @Version / updated_at.
--
-- SYNTHETIC data only for patients; public code vocabularies (real-format ICD-10/HCPCS/CPT values)
-- are reference data, not PHI, so seeding a small illustrative set is fine.

CREATE TABLE medical_code (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code_system VARCHAR(16)  NOT NULL,                 -- ICD10CM (diagnosis) | HCPCS | CPT (procedure)
    code        VARCHAR(16)  NOT NULL,                 -- the code itself, e.g. 'E11.9', '99213'
    description VARCHAR(300) NOT NULL,                 -- human-readable meaning
    active      BOOLEAN      NOT NULL DEFAULT true,    -- retired codes stay for history but drop out of search
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT medical_code_system_chk CHECK (code_system IN ('ICD10CM', 'HCPCS', 'CPT')),
    -- A code is unique within its system (the same string can exist across systems).
    CONSTRAINT ux_medical_code_system_code UNIQUE (code_system, code)
);

-- The unique (code_system, code) index also serves system-scoped code-prefix search. Description
-- search is a small-scale LIKE at synthetic volumes; no extra index needed for the demo data set.
