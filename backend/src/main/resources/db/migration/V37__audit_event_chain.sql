-- V37: Tamper-evident audit chain (Phase 7 slice 2). SYNTHETIC data only.
-- Turns the append-only audit_event log (V36) into a per-organization HMAC hash chain, so any edit, deletion,
-- reorder or insertion of a row can be DETECTED. Each event carries a per-org monotonic sequence_no, the prior
-- event's fingerprint (prev_hash), and its own fingerprint (entry_hash = HMAC-SHA256 over the event's canonical
-- form INCLUDING prev_hash, keyed by a per-org key derived from a master secret held in configuration, never in
-- this database). Change any field and its entry_hash no longer matches; the break also cascades to later rows.
--
-- audit_chain_head is the per-org chain tip: it holds the last entry_hash and the next sequence number, and is
-- taken under a PESSIMISTIC_WRITE lock at append time (the same "row lock" pattern as benefit_accumulator, §31),
-- so concurrent audit writes for one org serialize and the chain cannot fork or race. It also lets verification
-- detect truncation (deletion of the most recent rows).
--
-- NOTE: this migration assumes a FRESH / reset database. There is no production data and V36 shipped one commit
-- ago, so the new NOT NULL columns are added cleanly to an empty table. A local dev DB that already holds
-- slice-1 audit rows (without a chain) must be reset with ./scripts/db-reset.sh; the test DB is always fresh.

ALTER TABLE audit_event
    ADD COLUMN sequence_no BIGINT   NOT NULL,   -- per-org monotonic position in the chain (starts at 0)
    ADD COLUMN prev_hash   VARCHAR(64) NOT NULL,   -- the previous event's entry_hash (GENESIS = 64 zeros for the first)
    ADD COLUMN entry_hash  VARCHAR(64) NOT NULL;   -- HMAC-SHA256 hex of this event's canonical form (incl. prev_hash)

-- A tenant's chain positions are unique and dense; a gap or duplicate is itself evidence of tampering.
ALTER TABLE audit_event
    ADD CONSTRAINT ux_audit_event_org_sequence UNIQUE (organization_id, sequence_no);

-- The per-org chain tip. One row per organization; locked FOR UPDATE while appending an event.
CREATE TABLE audit_chain_head (
    organization_id UUID     PRIMARY KEY REFERENCES organization (id),
    last_hash       VARCHAR(64) NOT NULL,   -- entry_hash of the most recent event (GENESIS before the first)
    next_sequence   BIGINT   NOT NULL    -- sequence_no to assign to the next event
);
