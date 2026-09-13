-- V6: Service request — the core care-coordination aggregate (source-of-truth §14.4-14.6).
-- SYNTHETIC data only. Tenant-owned; a request is about a patient in the same organization.
-- This slice persists creation (DRAFT) + an immutable status-history row; the controlled state-machine
-- transitions (submit/triage/assign/review/approve/reject/cancel/close) arrive in the next slice.

CREATE TABLE service_request (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id      UUID         NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    priority        VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
    title           VARCHAR(200) NOT NULL,
    description     VARCHAR(2000),
    created_by      UUID         NOT NULL,                                 -- app_user who created it
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT service_request_type_chk CHECK (type IN
        ('CLAIM_SUPPORT','REFERRAL_REQUEST','DOCUMENT_REVIEW','APPOINTMENT_HELP','BENEFIT_CLARIFICATION')),
    CONSTRAINT service_request_status_chk CHECK (status IN
        ('DRAFT','SUBMITTED','TRIAGED','ASSIGNED','UNDER_REVIEW','NEEDS_INFORMATION',
         'APPROVED','REJECTED','CANCELLED','CLOSED')),
    CONSTRAINT service_request_priority_chk CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    -- Composite FK including organization_id: a request cannot reference another tenant's patient (§32.10).
    CONSTRAINT fk_service_request_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    -- Lets child rows (status history, comments, assignments) FK-with-org back to this request.
    CONSTRAINT ux_service_request_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_service_request_org_status  ON service_request (organization_id, status);
CREATE INDEX ix_service_request_org_patient ON service_request (organization_id, patient_id);

-- Immutable audit of every status change (append-only). from_status is NULL for the creation row.
CREATE TABLE request_status_history (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID        NOT NULL REFERENCES organization (id),
    service_request_id UUID        NOT NULL,
    from_status        VARCHAR(20),
    to_status          VARCHAR(20) NOT NULL,
    actor_user_id      UUID        NOT NULL,
    reason             VARCHAR(500),
    correlation_id     VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_status_history_request FOREIGN KEY (service_request_id, organization_id)
        REFERENCES service_request (id, organization_id)
);

CREATE INDEX ix_status_history_org_request ON request_status_history (organization_id, service_request_id);
