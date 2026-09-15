-- V17: Claim status history — the immutable audit of every claim status change (source-of-truth §Phase 4
-- submission/validation, §31.6). SYNTHETIC data only. Append-only: rows are never updated or deleted.
-- from_status is NULL for the creation row (null → DRAFT). Mirrors request_status_history.

CREATE TABLE claim_status_history (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization (id),
    claim_id        UUID        NOT NULL,
    from_status     VARCHAR(20),
    to_status       VARCHAR(20) NOT NULL,
    actor_user_id   UUID        NOT NULL,
    reason          VARCHAR(500),
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_claim_status_history_claim FOREIGN KEY (claim_id, organization_id)
        REFERENCES claim (id, organization_id)
);

CREATE INDEX ix_claim_status_history_org_claim ON claim_status_history (organization_id, claim_id);
