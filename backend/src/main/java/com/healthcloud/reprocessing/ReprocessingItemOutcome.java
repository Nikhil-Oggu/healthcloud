package com.healthcloud.reprocessing;

/**
 * The outcome of reprocessing one claim within a batch: the engine re-adjudicated it cleanly
 * ({@link #SUCCEEDED}, a new adjudication version was written) or the re-adjudication threw ({@link #FAILED},
 * the item carries a PHI-free reason). Each item is one claim's independent transaction, so one FAILED does not
 * roll back the others.
 */
public enum ReprocessingItemOutcome {
    SUCCEEDED,
    FAILED
}
