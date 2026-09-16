-- V28: Referral — a Phase-6 (advanced claims) care-coordination aggregate. SYNTHETIC data only.
-- A referral is a request that a patient be seen by a SPECIALTY, for a coded clinical reason (an ICD-10-CM
-- diagnosis). Tenant-owned and about a patient, so access inherits the patient object/relationship gate
-- (§21 layer 6, via PatientAccessGuard) — a top-level resource gated by its patient, exactly like claim,
-- prior_authorization and service_request (not nested under the patient route).
--
-- Like a claim/prior auth, a referral carries only coded, coordination-relevant data (a specialty + a diagnosis
-- code) — NO free-text clinical narrative — so it is not consent field-masked; a coordinator can route it
-- without unrestricted medical context. Slice 6 persists intake (REQUESTED) + the decision lifecycle
-- (APPROVED / DENIED / CANCELLED) with an immutable status history. The referral UI is a later slice.

CREATE TABLE referral (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id            UUID        NOT NULL,
    referral_number       VARCHAR(32) NOT NULL,                                -- human-readable, unique per tenant
    specialty             VARCHAR(100) NOT NULL,                               -- the target specialty (e.g. Cardiology)
    reason_code_system    VARCHAR(16) NOT NULL,
    reason_code           VARCHAR(16) NOT NULL,                                -- the coded reason (ICD-10-CM diagnosis)
    status                VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    decision_reason       VARCHAR(500),                                        -- required on DENIED (service-enforced)
    decided_by            UUID,                                                -- coordinator who approved/denied
    decided_at            TIMESTAMPTZ,
    requested_by          UUID        NOT NULL,                                -- app_user who requested it
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT referral_status_chk CHECK (status IN ('REQUESTED', 'APPROVED', 'DENIED', 'CANCELLED')),
    -- Composite FK including organization_id: a referral cannot reference another tenant's patient (§32.10).
    CONSTRAINT fk_referral_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    -- Structural integrity: the reason must be a real catalog code (global; no organization_id).
    CONSTRAINT fk_referral_reason FOREIGN KEY (reason_code_system, reason_code)
        REFERENCES medical_code (code_system, code),
    -- Referral numbers are unique within a tenant (different tenants may reuse the same string).
    CONSTRAINT ux_referral_org_number UNIQUE (organization_id, referral_number),
    -- Lets child rows (status history) FK-with-org back to this referral.
    CONSTRAINT ux_referral_id_org UNIQUE (id, organization_id)
);

CREATE INDEX ix_referral_org_status  ON referral (organization_id, status);
CREATE INDEX ix_referral_org_patient ON referral (organization_id, patient_id);

-- The immutable audit of every referral status change (§31.6). Append-only: never updated or deleted.
-- from_status is NULL for the creation row (null → REQUESTED). Mirrors prior_authorization_status_history.
CREATE TABLE referral_status_history (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),
    referral_id           UUID        NOT NULL,
    from_status           VARCHAR(20),
    to_status             VARCHAR(20) NOT NULL,
    actor_user_id         UUID        NOT NULL,
    reason                VARCHAR(500),
    correlation_id        VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_referral_history_referral FOREIGN KEY (referral_id, organization_id)
        REFERENCES referral (id, organization_id)
);

CREATE INDEX ix_referral_history_org_referral
    ON referral_status_history (organization_id, referral_id);
