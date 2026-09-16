-- V36: Security audit event — the Phase-7 (advanced security/governance) audit trail foundation. SYNTHETIC only.
-- An append-only record of a security-relevant action: who (actor) did what (action) to which resource
-- (resource_type + resource_id), when, in which tenant, with what outcome, tied to the request's correlation id.
-- This is the foundation the later Phase-7 slices build on — the tamper-evident per-org HMAC hash chain hangs
-- off these rows, break-glass records an event here, and retention/access-reviews read them.
--
-- Tenant-owned via organization_id (every read is org-scoped). Rows are IMMUTABLE and append-only: an audit
-- trail is never edited or deleted in-app, so there is no version column and no UPDATE/DELETE path. It is written
-- INSIDE the domain action's own transaction (§31.6), so the audit row commits atomically with the change it
-- records — or both roll back. It carries only PHI-free metadata (coded action/resource + a short, non-sensitive
-- detail), never a clinical narrative or patient identifier (rule 5), so it is not consent field-masked.

CREATE TABLE audit_event (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organization (id),  -- tenant key
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_user_id   UUID         REFERENCES app_user (id),               -- nullable: system/unauthenticated actions
    action          VARCHAR(60)  NOT NULL,                               -- CLAIM_ADJUDICATED / CONSENT_REVOKED / …
    resource_type   VARCHAR(40)  NOT NULL,                               -- CLAIM / CONSENT_DIRECTIVE / …
    resource_id     UUID,                                               -- the affected row (nullable)
    outcome         VARCHAR(10)  NOT NULL,                               -- SUCCESS / DENIED
    correlation_id  VARCHAR(64),                                        -- ties the event to the request log line
    detail          VARCHAR(500) NOT NULL,                               -- human-readable, PHI-free
    CONSTRAINT audit_event_outcome_chk CHECK (outcome IN ('SUCCESS', 'DENIED'))
);

-- The auditor's default view: a tenant's events newest-first.
CREATE INDEX ix_audit_event_org_time ON audit_event (organization_id, occurred_at DESC);
-- Drill into one resource's history (e.g. every event on a given claim).
CREATE INDEX ix_audit_event_org_resource ON audit_event (organization_id, resource_type, resource_id);
