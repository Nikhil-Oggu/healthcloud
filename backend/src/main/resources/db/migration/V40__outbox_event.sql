-- V40: Transactional outbox (Phase 8 slice 1). SYNTHETIC data only.
-- The event-driven foundation. A domain change and its resulting event must be recorded ATOMICALLY, but a Kafka
-- publish cannot join a database transaction (the dual-write problem: the DB commit and the broker send can't be
-- made atomic). The transactional outbox solves it: the domain change AND an outbox_event row commit in ONE
-- transaction (§31.6); a separate relay publishes the row to Kafka AFTER commit and stamps published_at. This
-- migration is the outbox table only — the relay + Kafka arrive in later Phase 8 slices.
--
-- Tenant-owned via organization_id. A row is IMMUTABLE except for the single relay stamp published_at (NULL = not
-- yet published — the pending state). The payload is a JSON document carrying MINIMUM-NECESSARY, PHI-free data
-- (no clinical narrative, no document content, no patient identifiers beyond an aggregate id) — event payloads
-- must never carry sensitive content (rule 5).

CREATE TABLE outbox_event (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organization (id),  -- tenant key
    aggregate_type  VARCHAR(64)  NOT NULL,                               -- CLAIM / REQUEST / DOCUMENT / …
    aggregate_id    UUID         NOT NULL,                               -- the aggregate the event is about
    event_type      VARCHAR(128) NOT NULL,                               -- claim.adjudicated / request.status-changed / …
    payload         TEXT         NOT NULL,                               -- JSON, minimum-necessary + PHI-free
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    correlation_id  VARCHAR(64),                                         -- ties the event to the request log line
    published_at    TIMESTAMPTZ                                          -- NULL until the relay publishes it
);

-- The relay's poll (a later slice): the oldest not-yet-published events, in order. A partial index so it stays
-- small — it holds only the pending backlog, not the whole (growing) history of published events.
CREATE INDEX ix_outbox_unpublished ON outbox_event (occurred_at) WHERE published_at IS NULL;
