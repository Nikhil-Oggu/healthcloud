-- V21: Benefit accumulator — a patient's cumulative spend under a coverage plan within a benefit year
-- (source-of-truth §Phase 5, §31 "row locks for financial accumulators"). SYNTHETIC data only.
--
-- Slice 1 applied the plan's full deductible on every claim (each claim behaved like the first of the year).
-- This table tracks how much of the annual deductible has already been met (and how much out-of-pocket has
-- accrued), so the adjudication engine can carry it across claims. The engine reads-and-updates a row inside
-- the adjudication transaction under a PESSIMISTIC_WRITE (SELECT ... FOR UPDATE) lock, so two concurrent
-- adjudications for the same patient/plan/year serialize and cannot lose an update.
--
-- benefit_year is the claim's service-date calendar year (an MVP simplification: real plan years need not align
-- to the calendar). out_of_pocket_met is tracked now but the out-of-pocket-max CAP is enforced in slice 3.
-- Tenant-owned and about a patient, so access inherits the patient gate (via the claim) at the service layer.

CREATE TABLE benefit_accumulator (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID          NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id         UUID          NOT NULL,
    coverage_plan_id   UUID          NOT NULL,
    benefit_year       INTEGER       NOT NULL,
    deductible_met     NUMERIC(12,2) NOT NULL DEFAULT 0,
    out_of_pocket_met  NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT benefit_accumulator_amounts_nonneg_chk CHECK (deductible_met >= 0 AND out_of_pocket_met >= 0),
    -- Composite FKs including organization_id: an accumulator cannot cross tenants (§32.10).
    CONSTRAINT fk_benefit_accumulator_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id),
    CONSTRAINT fk_benefit_accumulator_plan FOREIGN KEY (coverage_plan_id, organization_id)
        REFERENCES coverage_plan (id, organization_id),
    -- One accumulator per patient per plan per benefit year (the target of the insert-if-absent + locked read).
    CONSTRAINT ux_benefit_accumulator UNIQUE (organization_id, patient_id, coverage_plan_id, benefit_year)
);
