package com.healthcloud.retention;

import java.time.OffsetDateTime;

/**
 * The outcome of a retention run (source-of-truth §Phase 7): how many operational rows were purged, the policy
 * window in days, and the cutoff instant that was applied (grants that expired before this were removed).
 */
public record RetentionPurgeResultDto(long purgedCount, long retentionDays, OffsetDateTime cutoff) {
}
