package com.healthcloud.audit;

/**
 * The result of verifying an organization's audit chain (source-of-truth §Phase 7). {@code valid} is true when
 * every event's fingerprint and previous-hash link recompute correctly and the chain tip matches the head;
 * otherwise {@code brokenAtSequence} names the first failing position and {@code reason} says what failed (a
 * modified field, a broken link from a deleted/reordered/inserted row, or a truncated tail). {@code entriesChecked}
 * is how many events were validated before the verdict.
 */
public record AuditChainVerificationDto(
        boolean valid,
        int entriesChecked,
        Long brokenAtSequence,
        String reason) {

    public static AuditChainVerificationDto valid(int entriesChecked) {
        return new AuditChainVerificationDto(true, entriesChecked, null, null);
    }

    public static AuditChainVerificationDto broken(int entriesChecked, long brokenAtSequence, String reason) {
        return new AuditChainVerificationDto(false, entriesChecked, brokenAtSequence, reason);
    }
}
