package com.healthcloud.coverage;

/**
 * The kind of coverage plan (source-of-truth §Phase 4). Matches the {@code plan_type} CHECK on
 * {@code coverage_plan}. Labels are additive — never renumber or repurpose one.
 */
public enum PlanType {
    HMO,
    PPO,
    EPO,
    HDHP
}
