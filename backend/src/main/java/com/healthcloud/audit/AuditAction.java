package com.healthcloud.audit;

/**
 * The coded, security-relevant actions the audit trail records (source-of-truth §Phase 7). Stored as a string
 * (see {@link AuditEvent#action}); grows a value per audited action as coverage widens across later slices.
 * Slice 1 seeds the pattern with a money decision and a privacy decision.
 */
public enum AuditAction {

    /** A claim was adjudicated (or re-adjudicated) by the engine — a money decision. */
    CLAIM_ADJUDICATED,

    /** A consent directive was revoked — a privacy decision. */
    CONSENT_REVOKED,

    /** A provider broke the glass — a time-boxed emergency override of the relationship gate (§Phase 7). */
    BREAK_GLASS_INVOKED,

    /** An admin revoked a break-glass grant early during an access review (§Phase 7). */
    BREAK_GLASS_REVOKED
}
