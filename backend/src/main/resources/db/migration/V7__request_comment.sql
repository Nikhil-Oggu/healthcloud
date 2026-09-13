-- V7: Request comments — the collaboration thread on a service request (source-of-truth §14.5).
-- SYNTHETIC data only. Tenant-owned; a comment belongs to a request in the same organization.
-- Append-only from the API's point of view (this slice adds create + list; no edit/delete yet).

CREATE TABLE request_comment (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID         NOT NULL REFERENCES organization (id),   -- tenant key
    service_request_id UUID         NOT NULL,
    author_user_id     UUID         NOT NULL,                                -- app_user who wrote it
    body               VARCHAR(2000) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Composite FK including organization_id: a comment cannot attach to another tenant's request (§32.10).
    CONSTRAINT fk_request_comment_request FOREIGN KEY (service_request_id, organization_id)
        REFERENCES service_request (id, organization_id)
);

CREATE INDEX ix_request_comment_org_request ON request_comment (organization_id, service_request_id);
