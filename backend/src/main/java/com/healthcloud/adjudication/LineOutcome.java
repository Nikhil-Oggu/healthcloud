package com.healthcloud.adjudication;

/**
 * The per-line result of adjudication. {@link #COVERED} means the line's amounts were computed against a plan;
 * {@link #NOT_COVERED} means it fell under a denied claim (no coverage) or is a procedure the plan excludes
 * (§Phase 5); {@link #AUTH_REQUIRED} means the procedure requires prior authorization under the covering plan and
 * no APPROVED authorization covered the service date (§Phase 6); {@link #OUT_OF_NETWORK} means the claim's
 * rendering provider is not in the covering plan's network (§Phase 6 provider network). A
 * NOT_COVERED/AUTH_REQUIRED/OUT_OF_NETWORK line has the member owe the charge and does not accrue to the
 * deductible or out-of-pocket max. Labels are additive — never renumber or repurpose one (they back the
 * {@code adjudication_line.outcome} CHECK).
 */
public enum LineOutcome {
    COVERED,
    NOT_COVERED,
    AUTH_REQUIRED,
    OUT_OF_NETWORK
}
