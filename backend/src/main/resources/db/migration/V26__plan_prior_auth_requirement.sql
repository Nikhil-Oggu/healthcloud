-- V26: Plan prior-authorization requirements — procedure codes that REQUIRE prior authorization under a
-- coverage plan (source-of-truth §Phase 6, advanced claims). SYNTHETIC data only. When a claim is adjudicated
-- under the plan, a covered line whose procedure requires prior auth is paid only if an APPROVED
-- prior_authorization for that patient/plan/procedure covers the service date; otherwise the line comes out
-- AUTH_REQUIRED (allowed 0, plan pays 0, member owes the charge) and, like an exclusion, does not accrue to the
-- deductible or out-of-pocket max. Plan config (tenant-owned, NOT patient-scoped), like plan_exclusion; managed
-- by ORG_ADMIN.

CREATE TABLE plan_prior_auth_requirement (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    coverage_plan_id UUID        NOT NULL,
    code_system      VARCHAR(16) NOT NULL,
    code             VARCHAR(16) NOT NULL,
    created_by       UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Composite FK including organization_id: a requirement cannot cross tenants (§32.10).
    CONSTRAINT fk_plan_prior_auth_req_plan FOREIGN KEY (coverage_plan_id, organization_id)
        REFERENCES coverage_plan (id, organization_id),
    -- Structural integrity: the procedure must be a real catalog code (global; no organization_id).
    CONSTRAINT fk_plan_prior_auth_req_code FOREIGN KEY (code_system, code)
        REFERENCES medical_code (code_system, code),
    -- A plan requires prior auth for a given code at most once.
    CONSTRAINT ux_plan_prior_auth_req UNIQUE (organization_id, coverage_plan_id, code_system, code)
);

CREATE INDEX ix_plan_prior_auth_req_org_plan
    ON plan_prior_auth_requirement (organization_id, coverage_plan_id);
