package com.healthcloud.priorauth;

/**
 * The prior-authorization lifecycle states (source-of-truth §Phase 6, advanced claims). Matches the
 * {@code status} CHECK on {@code prior_authorization}. A request is created {@link #REQUESTED}; a reviewer
 * decides it ({@link #APPROVED} / {@link #DENIED}), or the requester {@link #CANCELLED}s it. Labels are
 * additive — never renumber or repurpose one. A later slice may add a NEEDS_INFO step.
 */
public enum PriorAuthorizationStatus {
    REQUESTED,
    APPROVED,
    DENIED,
    CANCELLED
}
