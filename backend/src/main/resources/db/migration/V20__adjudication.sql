-- V20: Adjudication — the deterministic, explainable outcome of adjudicating a claim (source-of-truth §Phase 5).
-- SYNTHETIC data only. An adjudication is an immutable snapshot: it records, for one claim, which coverage plan
-- applied and how every amount was computed (allowed, copay, deductible, coinsurance, plan-paid, member
-- responsibility) per line and in total. It is reached only by the adjudication engine (the dedicated
-- POST /api/v1/claims/{id}/adjudicate command), which moves an ACCEPTED claim to ADJUDICATED in one transaction
-- — a bare status change to ADJUDICATED is refused (like ASSIGNED on a service request).
--
-- Tenant-owned and about a claim (which is about a patient), so access inherits the patient object/relationship
-- gate (§21 layer 6, via PatientAccessGuard) at the service layer. Rows are append-only and never updated:
-- adjudication_version supports future re-adjudication (this slice writes version 1 only). The coverage_plan_id
-- and eligibility_id are null when the claim was denied for want of coverage on the service date.

CREATE TABLE adjudication (
    id                            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id               UUID          NOT NULL REFERENCES organization (id),   -- tenant key
    claim_id                      UUID          NOT NULL,
    adjudication_version          INTEGER       NOT NULL DEFAULT 1,                       -- future re-adjudication
    outcome                       VARCHAR(30)   NOT NULL,
    coverage_plan_id              UUID,                                                   -- null if denied (no coverage)
    eligibility_id                UUID,                                                   -- the covering enrollment, if any
    total_charge_amount           NUMERIC(12,2) NOT NULL,                                 -- sum of line charges (reference)
    total_allowed_amount          NUMERIC(12,2) NOT NULL,
    total_plan_paid_amount        NUMERIC(12,2) NOT NULL,
    total_member_responsibility   NUMERIC(12,2) NOT NULL,
    adjudicated_by                UUID          NOT NULL,                                 -- app_user who ran the engine
    adjudicated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    correlation_id                VARCHAR(64),
    CONSTRAINT adjudication_outcome_chk CHECK (outcome IN ('ADJUDICATED', 'DENIED_NO_ELIGIBILITY')),
    CONSTRAINT adjudication_amounts_nonneg_chk CHECK (
        total_charge_amount >= 0 AND total_allowed_amount >= 0
        AND total_plan_paid_amount >= 0 AND total_member_responsibility >= 0),
    -- Composite FK including organization_id: an adjudication cannot reference another tenant's claim (§32.10).
    CONSTRAINT fk_adjudication_claim FOREIGN KEY (claim_id, organization_id)
        REFERENCES claim (id, organization_id),
    -- One adjudication per claim per version (a claim is adjudicated once this slice).
    CONSTRAINT ux_adjudication_claim_version UNIQUE (organization_id, claim_id, adjudication_version),
    -- Lets child rows (adjudication lines) FK-with-org back to this adjudication.
    CONSTRAINT ux_adjudication_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_adjudication_org_claim ON adjudication (organization_id, claim_id);

-- The per-line breakdown: an immutable snapshot of how each claim line was computed. Charge and codes are
-- copied here so the adjudication reads back self-contained even if the claim line is later reworked.
CREATE TABLE adjudication_line (
    id                          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID          NOT NULL REFERENCES organization (id),
    adjudication_id             UUID          NOT NULL,
    claim_line_id               UUID          NOT NULL,
    line_number                 INTEGER       NOT NULL,
    procedure_code_system       VARCHAR(16)   NOT NULL,
    procedure_code              VARCHAR(16)   NOT NULL,
    outcome                     VARCHAR(20)   NOT NULL,
    charge_amount               NUMERIC(12,2) NOT NULL,
    allowed_amount              NUMERIC(12,2) NOT NULL,
    copay_amount                NUMERIC(12,2) NOT NULL,
    deductible_applied_amount   NUMERIC(12,2) NOT NULL,
    coinsurance_amount          NUMERIC(12,2) NOT NULL,
    plan_paid_amount            NUMERIC(12,2) NOT NULL,
    member_responsibility       NUMERIC(12,2) NOT NULL,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT adjudication_line_outcome_chk CHECK (outcome IN ('COVERED', 'NOT_COVERED')),
    -- Composite FK including organization_id: a line cannot attach to another tenant's adjudication (§32.10).
    CONSTRAINT fk_adjudication_line_adjudication FOREIGN KEY (adjudication_id, organization_id)
        REFERENCES adjudication (id, organization_id),
    CONSTRAINT ux_adjudication_line_number UNIQUE (adjudication_id, line_number)
);

CREATE INDEX ix_adjudication_line_org_adj ON adjudication_line (organization_id, adjudication_id);
