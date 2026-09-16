package com.healthcloud.anomaly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pure claim-anomaly policy (source-of-truth §Phase 6, advanced claims). Given a claim under scan and a
 * small {@link Context} of surrounding facts, it returns the advisory {@link DetectedSignal}s that apply —
 * deterministic, explainable heuristics with no Spring/DB dependencies, so it is unit-testable in isolation. A
 * thin {@code ClaimAnomalyService} loads the data and persists the result; this class only decides.
 *
 * <p>This is a pure-policy class like the state machines ({@code ClaimTransitions} et al.) and {@code
 * ConsentPolicy}, but of a new <b>detector</b> shape: it emits a list of findings rather than gating a
 * transition. The rules are synthetic demo heuristics — not a measured fraud model.
 */
public final class ClaimAnomalyDetector {

    private ClaimAnomalyDetector() {
    }

    /** The claim being scanned. Procedure codes are the raw code strings on its lines (e.g. {@code "99213"}). */
    public record Subject(String claimNumber, LocalDate serviceDate, BigDecimal totalChargeAmount,
                          Set<String> procedureCodes) {
    }

    /** A sibling claim for the same patient — the context duplicate detection compares against. */
    public record OtherClaim(String claimNumber, LocalDate serviceDate, Set<String> procedureCodes) {
    }

    /** Facts the detector needs beyond the subject itself. */
    public record Context(List<OtherClaim> otherClaims, BigDecimal highTotalChargeThreshold) {
    }

    /** One finding. {@code detail} is human-readable and PHI-free (claim numbers / procedure codes only). */
    public record DetectedSignal(AnomalySignalType type, AnomalySeverity severity, String detail) {
    }

    /**
     * Evaluate every rule against the subject and return the signals that fire, in a stable order (duplicates
     * first, then the high-total check). An empty list means the claim looks clean.
     */
    public static List<DetectedSignal> detect(Subject subject, Context context) {
        List<DetectedSignal> signals = new ArrayList<>();

        // Rule 1 — DUPLICATE_CLAIM (HIGH): another claim for the same patient on the same service date that
        // shares at least one procedure code. One signal per matching sibling, naming a shared code.
        for (OtherClaim other : context.otherClaims()) {
            if (!subject.serviceDate().equals(other.serviceDate())) {
                continue;
            }
            String shared = firstShared(subject.procedureCodes(), other.procedureCodes());
            if (shared != null) {
                signals.add(new DetectedSignal(
                        AnomalySignalType.DUPLICATE_CLAIM, AnomalySeverity.HIGH,
                        "Possible duplicate of claim " + other.claimNumber() + " (same service date "
                                + subject.serviceDate() + ", shared procedure " + shared + ")."));
            }
        }

        // Rule 2 — HIGH_TOTAL_CHARGE (MEDIUM): the backend-computed total exceeds the configured threshold.
        BigDecimal threshold = context.highTotalChargeThreshold();
        if (threshold != null && subject.totalChargeAmount() != null
                && subject.totalChargeAmount().compareTo(threshold) > 0) {
            signals.add(new DetectedSignal(
                    AnomalySignalType.HIGH_TOTAL_CHARGE, AnomalySeverity.MEDIUM,
                    "Total charge $" + subject.totalChargeAmount().toPlainString()
                            + " exceeds the review threshold $" + threshold.toPlainString() + "."));
        }

        return signals;
    }

    /** The lexicographically-first procedure code present in both sets, or null if they are disjoint. */
    private static String firstShared(Set<String> a, Set<String> b) {
        TreeSet<String> intersection = new TreeSet<>(a);
        intersection.retainAll(b);
        return intersection.isEmpty() ? null : intersection.first();
    }
}
