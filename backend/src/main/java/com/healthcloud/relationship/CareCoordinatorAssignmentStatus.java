package com.healthcloud.relationship;

/**
 * Lifecycle states of a care-coordinator↔patient assignment (source-of-truth §14.3). PENDING (future-dated)
 * and ACTIVE are the "current" states; EXPIRED (past its effective window) and REVOKED (ended) are terminal
 * history. An ACTIVE assignment makes the coordinator part of the patient's care team.
 *
 * <p>Slice scope: PENDING→ACTIVE / ACTIVE→EXPIRED are time-based transitions that need a scheduled sweep
 * (Phase 8+); this slice sets the initial status at write time and handles the explicit revoke transition.
 */
public enum CareCoordinatorAssignmentStatus {
    PENDING,
    ACTIVE,
    EXPIRED,
    REVOKED
}
