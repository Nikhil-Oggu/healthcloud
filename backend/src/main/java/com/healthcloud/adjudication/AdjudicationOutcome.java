package com.healthcloud.adjudication;

/**
 * The claim-level result of adjudication (source-of-truth §Phase 5). {@link #ADJUDICATED} means coverage was in
 * effect on the service date and the amounts were computed from the plan; {@link #DENIED_NO_ELIGIBILITY} means
 * no coverage covered the service date, so the plan pays nothing. Labels are additive — never renumber or
 * repurpose one (they back the {@code adjudication.outcome} CHECK).
 */
public enum AdjudicationOutcome {
    ADJUDICATED,
    DENIED_NO_ELIGIBILITY
}
