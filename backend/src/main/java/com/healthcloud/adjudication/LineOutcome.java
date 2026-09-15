package com.healthcloud.adjudication;

/**
 * The per-line result of adjudication (source-of-truth §Phase 5). {@link #COVERED} means the line's amounts were
 * computed against a plan; {@link #NOT_COVERED} means it fell under a denied claim (no coverage). Labels are
 * additive — never renumber or repurpose one (they back the {@code adjudication_line.outcome} CHECK).
 */
public enum LineOutcome {
    COVERED,
    NOT_COVERED
}
