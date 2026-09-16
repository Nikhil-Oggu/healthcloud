-- V32: Reprocessing batch — a Phase-6 (advanced claims) batch re-adjudication job. SYNTHETIC data only.
-- After a plan-config change (a fixed fee schedule, a new exclusion / prior-auth requirement, a retroactive
-- enrollment), an admin/reviewer re-runs the already-ADJUDICATED claims for a coverage plan through the
-- (unchanged) adjudication engine — each claim gets a new immutable adjudication version (§Phase 5 re-adjudication).
-- The batch is a JOB RECORD: it changes no adjudication math itself; it orchestrates the existing re-adjudication.
--
-- MVP runs the batch SYNCHRONOUSLY in the request and lands COMPLETED / COMPLETED_WITH_ERRORS (the async
-- outbox/worker version is Phase 8, like the async document scanner). It is deliberately NOT a state machine:
-- there are no client transitions, so no status-history table — the batch + its per-claim items are the record.
-- Tenant-owned; the batch scope is a coverage plan (admin config, not PHI), and each item is about a claim, so
-- the rows carry only claims-domain data (a claim id, an outcome, the new version, a PHI-free message).

CREATE TABLE reprocessing_batch (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    batch_number          VARCHAR(32) NOT NULL,                                -- human-readable, unique per tenant
    coverage_plan_id      UUID        NOT NULL,                                -- the scope: claims on this plan
    status                VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    total_count           INTEGER     NOT NULL DEFAULT 0,                      -- claims selected for reprocessing
    succeeded_count       INTEGER     NOT NULL DEFAULT 0,
    failed_count          INTEGER     NOT NULL DEFAULT 0,
    requested_by          UUID        NOT NULL,                                -- app_user who ran the batch
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at           TIMESTAMPTZ,
    CONSTRAINT reprocessing_batch_status_chk
        CHECK (status IN ('RUNNING', 'COMPLETED', 'COMPLETED_WITH_ERRORS')),
    -- Composite FK including organization_id: a batch cannot scope another tenant's plan (§32.10).
    CONSTRAINT fk_reprocessing_batch_plan FOREIGN KEY (coverage_plan_id, organization_id)
        REFERENCES coverage_plan (id, organization_id),
    -- Batch numbers are unique within a tenant (different tenants may reuse the same string).
    CONSTRAINT ux_reprocessing_batch_org_number UNIQUE (organization_id, batch_number),
    -- Lets child rows (items) FK-with-org back to this batch.
    CONSTRAINT ux_reprocessing_batch_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_reprocessing_batch_org_created ON reprocessing_batch (organization_id, created_at DESC);

-- One row per claim the batch reprocessed: the outcome plus, on success, the new adjudication version. Immutable.
CREATE TABLE reprocessing_item (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    reprocessing_batch_id UUID        NOT NULL,                                -- the batch this item belongs to
    claim_id              UUID        NOT NULL,                                -- the claim reprocessed
    outcome               VARCHAR(20) NOT NULL,                                -- SUCCEEDED / FAILED
    adjudication_version  INTEGER,                                             -- the new version (success only)
    message               VARCHAR(500),                                        -- PHI-free failure reason (failure only)
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reprocessing_item_outcome_chk CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    -- Composite FKs including organization_id: an item cannot reference another tenant's batch or claim (§32.10).
    CONSTRAINT fk_reprocessing_item_batch FOREIGN KEY (reprocessing_batch_id, organization_id)
        REFERENCES reprocessing_batch (id, organization_id),
    CONSTRAINT fk_reprocessing_item_claim FOREIGN KEY (claim_id, organization_id)
        REFERENCES claim (id, organization_id)
);

CREATE INDEX ix_reprocessing_item_org_batch ON reprocessing_item (organization_id, reprocessing_batch_id);
