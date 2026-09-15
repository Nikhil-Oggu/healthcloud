-- V16: Claim — the claims-intake aggregate (source-of-truth §Phase 4). SYNTHETIC data only.
-- A claim is a header plus one or more claim lines, each line billing a PROCEDURE code (CPT/HCPCS) from the
-- global medical_code catalog (V14). Tenant-owned and about a patient, so access inherits the patient
-- object/relationship gate (§21 layer 6, via PatientAccessGuard), like service_request.
--
-- This slice persists creation (DRAFT) only. Controlled status transitions + an immutable status-history
-- table + the submission/validation workflow arrive in the next slice; the status CHECK lists the forward
-- lifecycle now so those transitions need no schema change. A claim deliberately carries NO clinical
-- narrative — only coded, claim-relevant data — so a claims reviewer works claims without unrestricted
-- medical context (the Phase-4 §60 proof); the clinical narrative lives (consent-masked) in clinical_summary.

CREATE TABLE claim (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID          NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id          UUID          NOT NULL,
    claim_number        VARCHAR(32)   NOT NULL,                                -- human-readable, unique per tenant
    status              VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    service_date        DATE          NOT NULL,
    total_charge_amount NUMERIC(12,2) NOT NULL DEFAULT 0,                      -- sum of the line charges
    created_by          UUID          NOT NULL,                                -- app_user who created it
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT claim_status_chk CHECK (status IN
        ('DRAFT', 'SUBMITTED', 'ACCEPTED', 'REJECTED', 'ADJUDICATED', 'CANCELLED')),
    CONSTRAINT claim_total_nonneg_chk CHECK (total_charge_amount >= 0),
    -- Composite FK including organization_id: a claim cannot reference another tenant's patient (§32.10).
    CONSTRAINT fk_claim_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    -- Claim numbers are unique within a tenant (different tenants may reuse the same string).
    CONSTRAINT ux_claim_org_number UNIQUE (organization_id, claim_number),
    -- Lets child rows (claim lines, and later status history) FK-with-org back to this claim.
    CONSTRAINT ux_claim_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_claim_org_status  ON claim (organization_id, status);
CREATE INDEX ix_claim_org_patient ON claim (organization_id, patient_id);

-- One billed procedure per line. procedure_code_system + procedure_code point at the global catalog.
CREATE TABLE claim_line (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID          NOT NULL REFERENCES organization (id),
    claim_id              UUID          NOT NULL,
    line_number           INTEGER       NOT NULL,                              -- 1-based within the claim
    procedure_code_system VARCHAR(16)   NOT NULL,
    procedure_code        VARCHAR(16)   NOT NULL,
    units                 INTEGER       NOT NULL DEFAULT 1,
    charge_amount         NUMERIC(12,2) NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT claim_line_units_chk  CHECK (units > 0),
    CONSTRAINT claim_line_charge_chk CHECK (charge_amount >= 0),
    -- Composite FK including organization_id: a line cannot attach to another tenant's claim (§32.10).
    CONSTRAINT fk_claim_line_claim FOREIGN KEY (claim_id, organization_id)
        REFERENCES claim (id, organization_id),
    -- Structural integrity: the procedure must be a real catalog code (global; no organization_id).
    CONSTRAINT fk_claim_line_procedure FOREIGN KEY (procedure_code_system, procedure_code)
        REFERENCES medical_code (code_system, code),
    -- Line numbers are unique within a claim.
    CONSTRAINT ux_claim_line_number UNIQUE (claim_id, line_number)
);

CREATE INDEX ix_claim_line_org_claim ON claim_line (organization_id, claim_id);
