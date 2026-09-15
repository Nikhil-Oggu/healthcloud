package com.healthcloud.clinical;

/**
 * The kind of clinical note a summary records (source-of-truth §Phase 4 — "limited clinical summaries:
 * diagnosis/treatment/encounter"). Kept intentionally small; matches the {@code summary_type} CHECK constraint
 * on {@code clinical_summary}. Labels are additive — never renumber or repurpose one.
 */
public enum ClinicalSummaryType {
    ENCOUNTER,
    DIAGNOSIS,
    TREATMENT,
    LAB_RESULT
}
