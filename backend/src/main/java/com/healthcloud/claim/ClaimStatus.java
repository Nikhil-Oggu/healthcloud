package com.healthcloud.claim;

/**
 * The claim lifecycle states (source-of-truth §Phase 4–5). Matches the {@code status} CHECK on {@code claim}.
 * This slice creates claims in {@link #DRAFT} only; the controlled transitions (submit/validate → SUBMITTED,
 * accept/reject, adjudicate, cancel) arrive with the submission-workflow slice and the Phase-5 engine. Labels
 * are additive — never renumber or repurpose one.
 */
public enum ClaimStatus {
    DRAFT,
    SUBMITTED,
    ACCEPTED,
    REJECTED,
    ADJUDICATED,
    CANCELLED
}
