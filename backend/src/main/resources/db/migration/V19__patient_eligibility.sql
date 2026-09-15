-- V19: Patient eligibility — a patient's enrollment in a coverage plan for an effective-dated period
-- (source-of-truth §Phase 4 plan/eligibility foundations). SYNTHETIC data only. Tenant-owned and about a
-- patient, so access inherits the patient object/relationship gate (§21 layer 6, via PatientAccessGuard).
-- It is the bridge the Phase-5 adjudication engine walks: for a claim's patient + service date, find the
-- coverage in effect. Periods for a patient are kept non-overlapping (enforced in the service) so that
-- lookup is deterministic.

CREATE TABLE patient_eligibility (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id       UUID        NOT NULL,
    coverage_plan_id UUID        NOT NULL,
    member_id        VARCHAR(64) NOT NULL,                                -- the patient's number on the plan
    effective_from   DATE        NOT NULL,
    effective_to     DATE,                                                -- null = open-ended
    created_by       UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT patient_eligibility_dates_chk CHECK (effective_to IS NULL OR effective_to >= effective_from),
    -- Composite FKs including organization_id: eligibility cannot cross tenants (§32.10).
    CONSTRAINT fk_patient_eligibility_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    CONSTRAINT fk_patient_eligibility_plan FOREIGN KEY (coverage_plan_id, organization_id)
        REFERENCES coverage_plan (id, organization_id)
);

CREATE INDEX ix_patient_eligibility_org_patient ON patient_eligibility (organization_id, patient_id);
