package com.healthcloud.claimreview;

/**
 * The manual-review lifecycle states (source-of-truth §Phase 6, advanced claims). Matches the {@code status}
 * CHECK on {@code claim_review}. A review is created {@link #OPEN} (a coordinator/reviewer flags a claim for a
 * human look — often prompted by anomaly signals); a claims reviewer {@link #RESOLVED}s it with a conclusion, or
 * an opener {@link #CANCELLED}s it. Labels are additive — never renumber or repurpose one.
 */
public enum ClaimReviewStatus {
    OPEN,
    RESOLVED,
    CANCELLED
}
