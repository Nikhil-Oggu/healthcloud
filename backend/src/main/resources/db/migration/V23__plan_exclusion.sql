-- V23: Plan exclusions — procedure codes a coverage plan does NOT cover (source-of-truth §Phase 5). SYNTHETIC
-- data only. When a claim is adjudicated under the plan, a line whose procedure is excluded comes out
-- NOT_COVERED (allowed 0, plan pays 0, member owes the charge) even though coverage is in effect, and the
-- excluded charge does not count toward the deductible or out-of-pocket max. Plan config (tenant-owned, NOT
-- patient-scoped), like coverage_plan; managed by ORG_ADMIN.

CREATE TABLE plan_exclusion (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    coverage_plan_id UUID        NOT NULL,
    code_system      VARCHAR(16) NOT NULL,
    code             VARCHAR(16) NOT NULL,
    created_by       UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Composite FK including organization_id: an exclusion cannot cross tenants (§32.10).
    CONSTRAINT fk_plan_exclusion_plan FOREIGN KEY (coverage_plan_id, organization_id)
        REFERENCES coverage_plan (id, organization_id),
    -- Structural integrity: the excluded procedure must be a real catalog code (global; no organization_id).
    CONSTRAINT fk_plan_exclusion_code FOREIGN KEY (code_system, code)
        REFERENCES medical_code (code_system, code),
    -- A plan excludes a given code at most once.
    CONSTRAINT ux_plan_exclusion UNIQUE (organization_id, coverage_plan_id, code_system, code)
);

CREATE INDEX ix_plan_exclusion_org_plan ON plan_exclusion (organization_id, coverage_plan_id);
