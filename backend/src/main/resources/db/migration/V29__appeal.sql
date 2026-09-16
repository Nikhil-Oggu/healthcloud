-- V29: Appeal — a Phase-6 (advanced claims) aggregate. SYNTHETIC data only.
-- An appeal disputes a CLAIM's decision. Tenant-owned and about a claim (with the claim's patient_id
-- denormalized onto the row at creation, from the loaded claim — never the client), so access inherits the
-- patient object/relationship gate (§21 layer 6, via PatientAccessGuard) — a top-level resource gated by its
-- patient, exactly like claim, prior_authorization and referral (not nested under a route).
--
-- Like a claim, an appeal carries only claims-domain data (a dispute rationale + the decision) — NO clinical
-- narrative — so it is not consent field-masked; a claims reviewer can decide it without unrestricted medical
-- context. Slice 8 persists intake (SUBMITTED) + the resolution lifecycle (UPHELD / OVERTURNED / WITHDRAWN)
-- with an immutable status history. Wiring an OVERTURNED appeal into re-adjudication is a later slice.

CREATE TABLE appeal (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    claim_id              UUID        NOT NULL,                                -- the claim being appealed
    patient_id            UUID        NOT NULL,                                -- denormalized from the claim (gate + list scope)
    appeal_number         VARCHAR(32) NOT NULL,                                -- human-readable, unique per tenant
    reason                VARCHAR(1000) NOT NULL,                              -- the dispute rationale (claims-domain, not PHI)
    status                VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    decision_reason       VARCHAR(500),                                        -- required on every decision (service-enforced)
    decided_by            UUID,                                                -- reviewer who upheld/overturned
    decided_at            TIMESTAMPTZ,
    submitted_by          UUID        NOT NULL,                                -- app_user who submitted it
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT appeal_status_chk CHECK (status IN ('SUBMITTED', 'UPHELD', 'OVERTURNED', 'WITHDRAWN')),
    -- Composite FK including organization_id: an appeal cannot reference another tenant's claim (§32.10).
    CONSTRAINT fk_appeal_claim FOREIGN KEY (claim_id, organization_id)
        REFERENCES claim (id, organization_id),
    -- Composite FK including organization_id: nor another tenant's patient (§32.10).
    CONSTRAINT fk_appeal_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    -- Appeal numbers are unique within a tenant (different tenants may reuse the same string).
    CONSTRAINT ux_appeal_org_number UNIQUE (organization_id, appeal_number),
    -- Lets child rows (status history) FK-with-org back to this appeal.
    CONSTRAINT ux_appeal_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_appeal_org_status ON appeal (organization_id, status);
CREATE INDEX ix_appeal_org_claim  ON appeal (organization_id, claim_id);
CREATE INDEX ix_appeal_org_patient ON appeal (organization_id, patient_id);

-- The immutable audit of every appeal status change (§31.6). Append-only: never updated or deleted.
-- from_status is NULL for the creation row (null → SUBMITTED). Mirrors referral_status_history.
CREATE TABLE appeal_status_history (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),
    appeal_id             UUID        NOT NULL,
    from_status           VARCHAR(20),
    to_status             VARCHAR(20) NOT NULL,
    actor_user_id         UUID        NOT NULL,
    reason                VARCHAR(500),
    correlation_id        VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_appeal_history_appeal FOREIGN KEY (appeal_id, organization_id)
        REFERENCES appeal (id, organization_id)
);

CREATE INDEX ix_appeal_history_org_appeal
    ON appeal_status_history (organization_id, appeal_id);
