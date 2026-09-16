package com.healthcloud.appeal;

/**
 * The appeal lifecycle states (source-of-truth §Phase 6, advanced claims). Matches the {@code status} CHECK on
 * {@code appeal}. An appeal is created {@link #SUBMITTED}; a claims reviewer decides it ({@link #UPHELD} — the
 * original claim decision stands / {@link #OVERTURNED} — the decision is reversed), or the submitter
 * {@link #WITHDRAWN}s it. Labels are additive — never renumber or repurpose one. A later slice may add an
 * UNDER_REVIEW step and wire an OVERTURNED appeal into re-adjudication.
 */
public enum AppealStatus {
    SUBMITTED,
    UPHELD,
    OVERTURNED,
    WITHDRAWN
}
