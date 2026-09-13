package com.healthcloud.consent;

/**
 * The approved purposes-of-use a consent directive can govern (source-of-truth §22.2). Purpose is never
 * arbitrary client input — the backend defines the approved purpose for each action (§21.4); this enum is
 * the allowlist a recorded directive is validated against.
 */
public enum ConsentPurpose {
    CARE_COORDINATION,
    CLAIM_PROCESSING,
    DOCUMENT_REVIEW,
    APPOINTMENT_SUPPORT,
    BENEFIT_SUPPORT
}
