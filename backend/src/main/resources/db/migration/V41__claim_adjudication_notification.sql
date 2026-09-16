-- V41: Claim-adjudication notification feed (Phase 8 slice 3). SYNTHETIC data only.
-- The read side of the event pipeline: a Kafka consumer reacts to claim.adjudicated events (published by the
-- outbox relay, slice 2) and records one PHI-free notification per event — a downstream projection built purely
-- from the event, never re-reading the claim (loose coupling).
--
-- IDEMPOTENCY: the relay delivers at-least-once, so the same event may arrive more than once. event_id (the outbox
-- event's id, carried in a Kafka header) is UNIQUE — a redelivered event cannot create a second notification. The
-- consumer pre-checks event_id and this constraint is the backstop for any race.
--
-- Tenant-owned via organization_id; immutable (created by the consumer, never updated). The message carries only
-- claims/benefits data (claim number, amounts, outcome) — no clinical narrative or patient identifiers (rule 5).

CREATE TABLE claim_adjudication_notification (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organization (id),  -- tenant key
    claim_id        UUID         NOT NULL,                               -- the claim the event concerned
    event_id        UUID         NOT NULL UNIQUE,                        -- idempotency key (the outbox event id)
    message         VARCHAR(500) NOT NULL,                               -- human-readable, PHI-free
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Read the tenant's notifications newest-first (for a future read endpoint / UI).
CREATE INDEX ix_claim_adj_notification_org ON claim_adjudication_notification (organization_id, created_at DESC);
