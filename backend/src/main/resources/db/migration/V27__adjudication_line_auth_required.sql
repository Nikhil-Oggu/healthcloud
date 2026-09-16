-- V27: Add the AUTH_REQUIRED line outcome (source-of-truth §Phase 6). SYNTHETIC data only. A covered line whose
-- procedure requires prior authorization under the covering plan, with no APPROVED authorization covering the
-- service date, is adjudicated AUTH_REQUIRED — a distinct, explainable outcome (§60) from a plain NOT_COVERED
-- exclusion or a no-eligibility denial. Additive: existing rows are unaffected. Labels are additive — never
-- renumber or repurpose one (this matches the LineOutcome enum).

ALTER TABLE adjudication_line DROP CONSTRAINT adjudication_line_outcome_chk;
ALTER TABLE adjudication_line ADD CONSTRAINT adjudication_line_outcome_chk
    CHECK (outcome IN ('COVERED', 'NOT_COVERED', 'AUTH_REQUIRED'));
