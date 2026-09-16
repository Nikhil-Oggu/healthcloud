-- V33: Plan network providers — the PROVIDERs that participate in a coverage plan's network (source-of-truth
-- §Phase 6, advanced claims / provider network). SYNTHETIC data only. Plan config (tenant-owned, NOT
-- patient-scoped), like plan_exclusion / plan_prior_auth_requirement; managed by ORG_ADMIN.
--
-- Slice 17 is additive config only. Once a claim carries a rendering provider (slice 18) and the engine reads
-- this network (slice 19), a covered line rendered by a provider NOT in the covering plan's network comes out
-- OUT_OF_NETWORK (member owes the charge, plan pays 0) — but only when the plan actually defines a network
-- (no rows → no network restriction, so existing adjudications are unaffected).
--
-- Unlike the other plan-config tables (which reference the global medical_code catalog), this references a
-- PROVIDER, i.e. an app_user. app_user is NOT tenant-keyed, so there is no FK-with-org on the provider; the
-- service validates the provider is an active same-tenant PROVIDER (else 400), exactly as the assignment tables do.

CREATE TABLE plan_network_provider (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    coverage_plan_id UUID        NOT NULL,
    provider_user_id UUID        NOT NULL REFERENCES app_user (id),       -- the in-network provider
    created_by       UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Composite FK including organization_id: a network entry cannot cross tenants (§32.10).
    CONSTRAINT fk_plan_network_provider_plan FOREIGN KEY (coverage_plan_id, organization_id)
        REFERENCES coverage_plan (id, organization_id),
    -- A provider is in a plan's network at most once.
    CONSTRAINT ux_plan_network_provider UNIQUE (organization_id, coverage_plan_id, provider_user_id)
);

CREATE INDEX ix_plan_network_provider_org_plan
    ON plan_network_provider (organization_id, coverage_plan_id);
