-- V43: Dead-letter replay stamps (Phase 8 slice 6). SYNTHETIC data only.
-- Slice 5 drains failed records into dead_letter_event for inspection; this slice lets an ORG_ADMIN re-drive a stored
-- record back onto its source topic once the underlying cause is fixed. The row stays otherwise immutable — replay is
-- a one-way lifecycle stamp (mirroring break_glass_grant's revoked_*): replayed_at marks when, replayed_by marks who.
-- Both nullable; a null replayed_at means "not yet replayed". No new version column — the consumer is idempotent
-- (dedupes on event_id), so a re-publish is harmless and the stamp needs no optimistic lock.

ALTER TABLE dead_letter_event
    ADD COLUMN replayed_at TIMESTAMPTZ,                     -- when the record was replayed (null = not replayed)
    ADD COLUMN replayed_by UUID REFERENCES app_user (id);  -- the admin who replayed it
