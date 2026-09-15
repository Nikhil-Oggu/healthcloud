-- V22: Out-of-pocket-max enforcement (source-of-truth §Phase 5). SYNTHETIC data only.
-- Adds the per-line amount shifted from the member to the plan because the member reached the plan's annual
-- out-of-pocket maximum, so the adjudication breakdown reconciles: member_responsibility =
-- copay + deductible_applied + coinsurance - oop_max_applied. Additive; existing rows default to 0.

ALTER TABLE adjudication_line
    ADD COLUMN oop_max_applied_amount NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE adjudication_line
    ADD CONSTRAINT adjudication_line_oop_nonneg_chk CHECK (oop_max_applied_amount >= 0);
