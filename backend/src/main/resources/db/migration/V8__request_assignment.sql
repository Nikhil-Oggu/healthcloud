-- V8: Request assignment — who is responsible for working a service request (source-of-truth §14.5,
-- §32.4 request_assignment). SYNTHETIC data only. Tenant-owned; the assignee is a same-org provider or
-- claims reviewer. Assignment is versioned/audited (§31.7, §54): reassignment supersedes the prior row,
-- so at most one ACTIVE assignment exists per request while the full history is retained.

CREATE TABLE request_assignment (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    service_request_id  UUID        NOT NULL,
    assignee_user_id    UUID        NOT NULL REFERENCES app_user (id),
    assigned_by_user_id UUID        NOT NULL REFERENCES app_user (id),
    assignee_role       VARCHAR(40) NOT NULL,                                -- role code at assignment time
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,                                         -- set when superseded
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT request_assignment_status_chk CHECK (status IN ('ACTIVE','SUPERSEDED')),
    -- Composite FK including organization_id: an assignment cannot attach to another tenant's request (§32.10).
    CONSTRAINT fk_request_assignment_request FOREIGN KEY (service_request_id, organization_id)
        REFERENCES service_request (id, organization_id)
);

-- At most one ACTIVE assignment per request (also a concurrency backstop: a racing second assign fails here).
CREATE UNIQUE INDEX ux_request_assignment_active ON request_assignment (service_request_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_request_assignment_org_request  ON request_assignment (organization_id, service_request_id);
-- Supports "requests assigned to me" queries (Phase 3+).
CREATE INDEX ix_request_assignment_org_assignee ON request_assignment (organization_id, assignee_user_id)
    WHERE status = 'ACTIVE';
