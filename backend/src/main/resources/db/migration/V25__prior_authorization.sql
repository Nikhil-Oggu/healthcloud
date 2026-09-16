-- V25: Prior authorization — the first Phase-6 (advanced claims) aggregate. SYNTHETIC data only.
-- A prior authorization is a request that a PLANNED procedure be pre-approved under a patient's coverage
-- before the service is rendered. Tenant-owned and about a patient, so access inherits the patient
-- object/relationship gate (§21 layer 6, via PatientAccessGuard) — a top-level resource gated by its patient,
-- exactly like claim and service_request (not nested under the patient route).
--
-- Like a claim, a prior auth carries only coded, claim-relevant data (a procedure code + a service window +
-- the coverage plan) — NO clinical narrative — so it is not consent field-masked; a claims reviewer can decide
-- it without unrestricted medical context. Slice 1 persists intake (REQUESTED) + the decision lifecycle
-- (APPROVED / DENIED / CANCELLED) with an immutable status history. Wiring an APPROVED auth into the
-- adjudication engine (a claim line that requires prior auth) is a later Phase-6 slice.

CREATE TABLE prior_authorization (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id             UUID        NOT NULL,
    auth_number            VARCHAR(32) NOT NULL,                                -- human-readable, unique per tenant
    coverage_plan_id       UUID        NOT NULL,                                -- the plan the auth is sought under
    procedure_code_system  VARCHAR(16) NOT NULL,
    procedure_code         VARCHAR(16) NOT NULL,
    requested_service_from DATE        NOT NULL,                                -- planned service window (start)
    requested_service_to   DATE,                                               -- optional window end
    status                 VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    decision_reason        VARCHAR(500),                                       -- required on DENIED (service-enforced)
    decided_by             UUID,                                               -- reviewer who approved/denied
    decided_at             TIMESTAMPTZ,
    requested_by           UUID        NOT NULL,                                -- app_user who requested it
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT prior_auth_status_chk CHECK (status IN ('REQUESTED', 'APPROVED', 'DENIED', 'CANCELLED')),
    CONSTRAINT prior_auth_window_chk CHECK (requested_service_to IS NULL
        OR requested_service_to >= requested_service_from),
    -- Composite FK including organization_id: a prior auth cannot reference another tenant's patient (§32.10).
    CONSTRAINT fk_prior_auth_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    -- Composite FK including organization_id: nor another tenant's coverage plan (§32.10).
    CONSTRAINT fk_prior_auth_plan FOREIGN KEY (coverage_plan_id, organization_id)
        REFERENCES coverage_plan (id, organization_id),
    -- Structural integrity: the procedure must be a real catalog code (global; no organization_id).
    CONSTRAINT fk_prior_auth_procedure FOREIGN KEY (procedure_code_system, procedure_code)
        REFERENCES medical_code (code_system, code),
    -- Auth numbers are unique within a tenant (different tenants may reuse the same string).
    CONSTRAINT ux_prior_auth_org_number UNIQUE (organization_id, auth_number),
    -- Lets child rows (status history) FK-with-org back to this prior auth.
    CONSTRAINT ux_prior_auth_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_prior_auth_org_status  ON prior_authorization (organization_id, status);
CREATE INDEX ix_prior_auth_org_patient ON prior_authorization (organization_id, patient_id);

-- The immutable audit of every prior-auth status change (§31.6). Append-only: never updated or deleted.
-- from_status is NULL for the creation row (null → REQUESTED). Mirrors claim_status_history.
CREATE TABLE prior_authorization_status_history (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),
    prior_authorization_id UUID       NOT NULL,
    from_status           VARCHAR(20),
    to_status             VARCHAR(20) NOT NULL,
    actor_user_id         UUID        NOT NULL,
    reason                VARCHAR(500),
    correlation_id        VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_prior_auth_history_auth FOREIGN KEY (prior_authorization_id, organization_id)
        REFERENCES prior_authorization (id, organization_id)
);

CREATE INDEX ix_prior_auth_history_org_auth
    ON prior_authorization_status_history (organization_id, prior_authorization_id);
