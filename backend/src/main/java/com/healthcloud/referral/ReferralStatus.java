package com.healthcloud.referral;

/**
 * The referral lifecycle states (source-of-truth §Phase 6, advanced claims). Matches the {@code status} CHECK on
 * {@code referral}. A referral is created {@link #REQUESTED}; a care coordinator decides it ({@link #APPROVED} /
 * {@link #DENIED}), or the requester {@link #CANCELLED}s it. Labels are additive — never renumber or repurpose
 * one. A later slice may add SCHEDULED / COMPLETED steps.
 */
public enum ReferralStatus {
    REQUESTED,
    APPROVED,
    DENIED,
    CANCELLED
}
