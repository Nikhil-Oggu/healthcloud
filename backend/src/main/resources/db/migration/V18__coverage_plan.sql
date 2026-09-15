-- V18: Coverage plan — a benefit plan the organization administers (source-of-truth §Phase 4 plan/eligibility
-- foundations). SYNTHETIC data only. Tenant-owned (each org administers its own plans) but NOT patient-scoped:
-- it is administrative benefit config, not PHI, so it uses the tenant pattern (org-scoped finders, cross-tenant
-- → secure 404) with no PatientAccessGuard. It defines the benefit parameters the Phase-5 adjudication engine
-- will apply (deductible, copay, coinsurance, out-of-pocket max); patient eligibility (which patient is on which
-- plan, and when) is the next slice.

CREATE TABLE coverage_plan (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID          NOT NULL REFERENCES organization (id),   -- tenant key
    plan_code           VARCHAR(32)   NOT NULL,                                -- unique per tenant
    name                VARCHAR(200)  NOT NULL,
    plan_type           VARCHAR(10)   NOT NULL,
    deductible_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,
    coinsurance_rate    NUMERIC(5,4)  NOT NULL DEFAULT 0,                      -- member share after deductible
    copay_amount        NUMERIC(12,2) NOT NULL DEFAULT 0,
    out_of_pocket_max   NUMERIC(12,2),                                         -- nullable: no cap
    active              BOOLEAN       NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT coverage_plan_type_chk CHECK (plan_type IN ('HMO', 'PPO', 'EPO', 'HDHP')),
    CONSTRAINT coverage_plan_deductible_chk CHECK (deductible_amount >= 0),
    CONSTRAINT coverage_plan_copay_chk CHECK (copay_amount >= 0),
    CONSTRAINT coverage_plan_oop_chk CHECK (out_of_pocket_max IS NULL OR out_of_pocket_max >= 0),
    CONSTRAINT coverage_plan_coinsurance_chk CHECK (coinsurance_rate >= 0 AND coinsurance_rate <= 1),
    -- Plan codes are unique within a tenant (different tenants may reuse the same code).
    CONSTRAINT ux_coverage_plan_org_code UNIQUE (organization_id, plan_code),
    -- Lets later child rows (patient eligibility) FK-with-org back to this plan (§32.10).
    CONSTRAINT ux_coverage_plan_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_coverage_plan_org ON coverage_plan (organization_id);
