-- V31: Claim review — a Phase-6 (advanced claims) manual-review aggregate. SYNTHETIC data only.
-- A manual review flags a CLAIM for a human look (often prompted by anomaly signals). Tenant-owned and about a
-- claim (with the claim's patient_id denormalized onto the row at creation, from the loaded claim — never the
-- client), so access inherits the patient object/relationship gate (§21 layer 6, via PatientAccessGuard) — a
-- top-level resource gated by its patient, exactly like claim, prior_authorization, referral and appeal.
--
-- Like a claim, a review carries only claims-domain data (why it was opened + the reviewer's conclusion) — NO
-- clinical narrative — so it is not consent field-masked. Slice 13 persists intake (OPEN) + the resolution
-- lifecycle (RESOLVED / CANCELLED) with an immutable status history. A review is a TRACKING record: it neither
-- holds the claim nor changes its status.

CREATE TABLE claim_review (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    claim_id              UUID        NOT NULL,                                -- the claim under review
    patient_id            UUID        NOT NULL,                                -- denormalized from the claim (gate + list scope)
    review_number         VARCHAR(32) NOT NULL,                                -- human-readable, unique per tenant
    reason                VARCHAR(1000),                                       -- why opened (optional, claims-domain, not PHI)
    status                VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolution            VARCHAR(500),                                        -- the reviewer's conclusion (required to resolve)
    opened_by             UUID        NOT NULL,                                -- app_user who opened it
    resolved_by           UUID,                                                -- reviewer who resolved it
    resolved_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT claim_review_status_chk CHECK (status IN ('OPEN', 'RESOLVED', 'CANCELLED')),
    -- Composite FK including organization_id: a review cannot reference another tenant's claim (§32.10).
    CONSTRAINT fk_claim_review_claim FOREIGN KEY (claim_id, organization_id)
        REFERENCES claim (id, organization_id),
    -- Composite FK including organization_id: nor another tenant's patient (§32.10).
    CONSTRAINT fk_claim_review_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    -- Review numbers are unique within a tenant (different tenants may reuse the same string).
    CONSTRAINT ux_claim_review_org_number UNIQUE (organization_id, review_number),
    -- Lets child rows (status history) FK-with-org back to this review.
    CONSTRAINT ux_claim_review_id_org UNIQUE (id, organization_id)
);

-- At most one OPEN review per claim (a second open → 409; the partial unique index backstops races).
CREATE UNIQUE INDEX ux_claim_review_one_open
    ON claim_review (organization_id, claim_id) WHERE status = 'OPEN';

CREATE INDEX ix_claim_review_org_status  ON claim_review (organization_id, status);
CREATE INDEX ix_claim_review_org_claim   ON claim_review (organization_id, claim_id);
CREATE INDEX ix_claim_review_org_patient ON claim_review (organization_id, patient_id);

-- The immutable audit of every review status change (§31.6). Append-only: never updated or deleted.
-- from_status is NULL for the creation row (null → OPEN). Mirrors appeal_status_history.
CREATE TABLE claim_review_status_history (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),
    claim_review_id       UUID        NOT NULL,
    from_status           VARCHAR(20),
    to_status             VARCHAR(20) NOT NULL,
    actor_user_id         UUID        NOT NULL,
    reason                VARCHAR(500),
    correlation_id        VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_claim_review_history_review FOREIGN KEY (claim_review_id, organization_id)
        REFERENCES claim_review (id, organization_id)
);

CREATE INDEX ix_claim_review_history_org_review
    ON claim_review_status_history (organization_id, claim_review_id);
