-- V10: Provider-patient assignment — the care relationship that controls provider access to a patient
-- (source-of-truth §14.3, §32.4). SYNTHETIC data only. Organization-scoped, effective-dated, and auditable:
-- assignment/revocation are recorded, never deleted. A provider requires an ACTIVE relationship for ordinary
-- clinical access (the object/relationship gate is enforced in the NEXT slice — this slice only records it).
--
-- States (§14.3): PENDING (future-dated), ACTIVE (in force), EXPIRED (past its window), REVOKED (ended).
-- Many providers may be assigned to one patient, so the "one current" invariant is per (patient, provider):
-- at most one CURRENT (ACTIVE/PENDING) assignment per pair.

CREATE TABLE provider_patient_assignment (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id          UUID        NOT NULL,
    provider_user_id    UUID        NOT NULL REFERENCES app_user (id),
    assigned_by_user_id UUID        NOT NULL REFERENCES app_user (id),
    status              VARCHAR(20) NOT NULL,
    effective_from      DATE        NOT NULL,
    effective_to        DATE,                                               -- open-ended when null
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,                                        -- set when revoked
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT provider_patient_assignment_status_chk CHECK (status IN
        ('PENDING','ACTIVE','EXPIRED','REVOKED')),
    -- Composite FK including organization_id: an assignment cannot attach to another tenant's patient (§32.10).
    CONSTRAINT fk_ppa_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id)
);

-- At most one CURRENT (ACTIVE/PENDING) assignment per (patient, provider) — the "already assigned" guard and
-- a concurrency backstop for racing assigns. Revoked/expired rows are retained as history and free the slot.
CREATE UNIQUE INDEX ux_ppa_current ON provider_patient_assignment (patient_id, provider_user_id)
    WHERE status IN ('ACTIVE','PENDING');

CREATE INDEX ix_ppa_org_patient ON provider_patient_assignment (organization_id, patient_id);
-- Supports the next slice's "patients assigned to me" lookups.
CREATE INDEX ix_ppa_org_provider ON provider_patient_assignment (organization_id, provider_user_id)
    WHERE status = 'ACTIVE';
