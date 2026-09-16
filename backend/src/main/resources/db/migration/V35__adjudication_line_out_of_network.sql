-- V35: Add the OUT_OF_NETWORK line outcome (source-of-truth §Phase 6, provider network). SYNTHETIC data only.
-- A covered line on a claim whose rendering provider is NOT in the covering plan's network (and the plan defines
-- one) is adjudicated OUT_OF_NETWORK — a distinct, explainable outcome (§60) from a plain NOT_COVERED exclusion,
-- an AUTH_REQUIRED gap, or a no-eligibility denial. Like those, the member owes the charge and it does not accrue
-- to the deductible / out-of-pocket max. Additive: existing rows are unaffected. Labels are additive — never
-- renumber or repurpose one (this matches the LineOutcome enum).

ALTER TABLE adjudication_line DROP CONSTRAINT adjudication_line_outcome_chk;
ALTER TABLE adjudication_line ADD CONSTRAINT adjudication_line_outcome_chk
    CHECK (outcome IN ('COVERED', 'NOT_COVERED', 'AUTH_REQUIRED', 'OUT_OF_NETWORK'));
