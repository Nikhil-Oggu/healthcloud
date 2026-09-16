package com.healthcloud.audit;

/**
 * The outcome of an audited action (source-of-truth §Phase 7). A successful action or a denied attempt are both
 * worth recording; slice 1 records {@link #SUCCESS}, and {@link #DENIED} is available for the access-decision
 * events later slices add (e.g. break-glass or a rejected privileged read).
 */
public enum AuditOutcome {
    SUCCESS,
    DENIED
}
