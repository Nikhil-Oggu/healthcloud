-- V24: Plan fee schedule — the allowed amount a coverage plan will recognize for a procedure (source-of-truth
-- §Phase 5). SYNTHETIC data only. Until now the adjudication engine used allowed = charge; a fee-schedule entry
-- makes allowed = min(charge, allowed_amount) for that procedure under this plan (the standard in-network model,
-- where charge - allowed is a provider write-off no one pays). Everything downstream (copay, deductible,
-- coinsurance, out-of-pocket, plan/member split) already keys off the allowed amount. Plan config (tenant-owned,
-- NOT patient-scoped), like coverage_plan and plan_exclusion; managed by ORG_ADMIN.

CREATE TABLE plan_fee_schedule (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organization (id),   -- tenant key
    coverage_plan_id UUID          NOT NULL,
    code_system      VARCHAR(16)   NOT NULL,
    code             VARCHAR(16)   NOT NULL,
    allowed_amount   NUMERIC(12,2) NOT NULL,
    created_by       UUID          NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Composite FK including organization_id: a fee-schedule entry cannot cross tenants (§32.10).
    CONSTRAINT fk_plan_fee_schedule_plan FOREIGN KEY (coverage_plan_id, organization_id)
        REFERENCES coverage_plan (id, organization_id),
    -- Structural integrity: the priced procedure must be a real catalog code (global; no organization_id).
    CONSTRAINT fk_plan_fee_schedule_code FOREIGN KEY (code_system, code)
        REFERENCES medical_code (code_system, code),
    -- A plan prices a given code at most once.
    CONSTRAINT ux_plan_fee_schedule UNIQUE (organization_id, coverage_plan_id, code_system, code),
    CONSTRAINT plan_fee_schedule_allowed_chk CHECK (allowed_amount >= 0)
);

CREATE INDEX ix_plan_fee_schedule_org_plan ON plan_fee_schedule (organization_id, coverage_plan_id);
