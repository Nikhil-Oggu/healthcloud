-- V42: Dead-letter event store (Phase 8 slice 5). SYNTHETIC data only.
-- The consumer's dead-letter topic (<topic>.DLT, slice 4) parks records that failed processing. Inspecting a Kafka
-- topic over REST is awkward, so a dedicated drainer consumes the DLT into this table — turning "what's dead-lettered"
-- into an ordinary tenant/role-gated read (and, in a later slice, a DB-driven replay).
--
-- Tenant-owned via organization_id (from the original event's header) — NULLABLE, because a fully-unattributable
-- poison record may carry no tenant. Immutable: the drainer inserts, nothing updates it (replay's status/replayed_*
-- columns arrive in a later slice). The (dlt_topic, dlt_partition, dlt_offset) of the DLT record is unique, so the
-- drainer is idempotent if a DLT record is redelivered. Payloads/exception text are claims-domain, PHI-free (rule 5).

CREATE TABLE dead_letter_event (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID          REFERENCES organization (id),  -- nullable: unattributable poison has no tenant
    source_topic      VARCHAR(128)  NOT NULL,                      -- the original topic the record failed on
    message_key       VARCHAR(256),                                -- the original Kafka key (e.g. aggregate id)
    payload           TEXT          NOT NULL,                      -- the original message value (JSON), PHI-free
    event_id          UUID,                                        -- the outbox event id, if the header was present
    exception_type    VARCHAR(256)  NOT NULL,                      -- the failure class (e.g. a Jackson exception)
    exception_message VARCHAR(1000),                               -- the failure message (truncated), PHI-free
    dlt_topic         VARCHAR(128)  NOT NULL,                      -- the DLT the record was read from
    dlt_partition     INTEGER       NOT NULL,
    dlt_offset        BIGINT        NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Idempotent drain: one row per DLT record, even if the drainer redelivers it.
    CONSTRAINT ux_dead_letter_dlt_coords UNIQUE (dlt_topic, dlt_partition, dlt_offset)
);

-- Read a tenant's dead letters newest-first (the admin inspection list).
CREATE INDEX ix_dead_letter_org ON dead_letter_event (organization_id, created_at DESC);
