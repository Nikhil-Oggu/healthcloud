package com.healthcloud.anomaly;

/**
 * The kinds of anomaly the deterministic {@link ClaimAnomalyDetector} can raise on a claim (source-of-truth
 * §Phase 6, advanced claims). Synthetic, explainable heuristics — not a measured fraud model.
 */
public enum AnomalySignalType {
    /** Another claim for the same patient shares this claim's service date and at least one procedure code. */
    DUPLICATE_CLAIM,
    /** The claim's backend-computed total charge exceeds the configured high-charge threshold. */
    HIGH_TOTAL_CHARGE
}
