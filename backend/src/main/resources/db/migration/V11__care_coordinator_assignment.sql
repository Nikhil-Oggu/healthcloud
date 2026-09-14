-- V11: Care-coordinator↔patient assignment — the other half of the patient's care team (source-of-truth
-- §14.3, §22). SYNTHETIC data only. Organization-scoped, effective-dated, and auditable: assignment and
-- revocation are recorded, never deleted. Mirrors provider_patient_assignment (V10); together the two tables
-- define the care team a CARE_TEAM-scoped consent directive applies to (the consent wiring is the NEXT slice —
-- this slice only records the relationship).
--
-- States (§14.3): PENDING (future-dated), ACTIVE (in force), EXPIRED (past its window), REVOKED (ended).
-- Many coordinators may serve one patient, so the "one current" invariant is per (patient, coordinator):
-- at most one CURRENT (ACTIVE/PENDING) assignment per pair.

CREATE TABLE care_coordinator_assignment (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id           UUID        NOT NULL,
    coordinator_user_id  UUID        NOT NULL REFERENCES app_user (id),
    assigned_by_user_id  UUID        NOT NULL REFERENCES app_user (id),
    status               VARCHAR(20) NOT NULL,
    effective_from       DATE        NOT NULL,
    effective_to         DATE,                                               -- open-ended when null
    assigned_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at             TIMESTAMPTZ,                                        -- set when revoked
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT care_coordinator_assignment_status_chk CHECK (status IN
        ('PENDING','ACTIVE','EXPIRED','REVOKED')),
    -- Composite FK including organization_id: an assignment cannot attach to another tenant's patient (§32.10).
    CONSTRAINT fk_cca_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id)
);

-- At most one CURRENT (ACTIVE/PENDING) assignment per (patient, coordinator) — the "already assigned" guard
-- and a concurrency backstop for racing assigns. Revoked/expired rows are retained as history and free the slot.
CREATE UNIQUE INDEX ux_cca_current ON care_coordinator_assignment (patient_id, coordinator_user_id)
    WHERE status IN ('ACTIVE','PENDING');

CREATE INDEX ix_cca_org_patient ON care_coordinator_assignment (organization_id, patient_id);
-- Supports care-team membership lookups ("patients I coordinate") for the consent wiring next slice.
CREATE INDEX ix_cca_org_coordinator ON care_coordinator_assignment (organization_id, coordinator_user_id)
    WHERE status = 'ACTIVE';
