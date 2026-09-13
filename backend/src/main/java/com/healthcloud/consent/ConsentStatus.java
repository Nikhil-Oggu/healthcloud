package com.healthcloud.consent;

/**
 * The lifecycle states of a consent directive (source-of-truth §22.4). SCHEDULED (future-dated) and ACTIVE
 * are the "current" states; REVOKED, EXPIRED and SUPERSEDED are terminal history. Activated consent is
 * immutable — a change SUPERSEDES the current version and inserts a new one; revocation flips it to REVOKED.
 *
 * <p>Slice-1 scope: SCHEDULED→ACTIVE and ACTIVE→EXPIRED are time-based transitions that need a scheduled
 * sweep (Phase 8+); this slice computes the initial status at write time and handles the explicit
 * supersede/revoke transitions. EXPIRED is defined here for completeness.
 */
public enum ConsentStatus {
    SCHEDULED,
    ACTIVE,
    REVOKED,
    EXPIRED,
    SUPERSEDED
}
