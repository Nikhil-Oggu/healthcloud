package com.healthcloud.reprocessing;

/**
 * The lifecycle of a reprocessing batch (source-of-truth §Phase 6, advanced claims). MVP runs a batch
 * synchronously in the request, so a batch is briefly {@link #RUNNING} while the engine re-adjudicates each
 * selected claim and then lands terminal — {@link #COMPLETED} if every claim reprocessed cleanly, or
 * {@link #COMPLETED_WITH_ERRORS} if at least one failed (its {@code reprocessing_item} carries the reason).
 *
 * <p>This is deliberately <b>not</b> a client-driven state machine: there are no {@code PATCH} transitions —
 * the batch is created, runs and finishes in one call. (A crashed synchronous batch can be left RUNNING with no
 * recovery yet; the async, recoverable worker version is Phase 8.)
 */
public enum ReprocessingBatchStatus {
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ERRORS
}
