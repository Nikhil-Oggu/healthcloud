package com.healthcloud.anomaly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.anomaly.ClaimAnomalyDetector.Context;
import com.healthcloud.anomaly.ClaimAnomalyDetector.DetectedSignal;
import com.healthcloud.anomaly.ClaimAnomalyDetector.OtherClaim;
import com.healthcloud.anomaly.ClaimAnomalyDetector.Subject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link ClaimAnomalyDetector} — no Spring/DB. Proves the deterministic heuristics fire
 * exactly when they should: a same-date, shared-procedure sibling is a duplicate; different dates or disjoint
 * codes are not; a total over the threshold is flagged; a clean claim yields nothing.
 */
class ClaimAnomalyDetectorTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("5000.00");
    private static final LocalDate NOV_1 = LocalDate.of(2025, 11, 1);

    private static Subject subject(BigDecimal total, String... codes) {
        return new Subject("CLM-SUBJECT", NOV_1, total, Set.of(codes));
    }

    @Test
    void flags_a_duplicate_claim_same_date_and_shared_procedure() {
        Subject subject = subject(new BigDecimal("150.00"), "99213", "80053");
        OtherClaim sibling = new OtherClaim("CLM-SIBLING", NOV_1, Set.of("99213"));

        List<DetectedSignal> signals =
                ClaimAnomalyDetector.detect(subject, new Context(List.of(sibling), THRESHOLD));

        assertEquals(1, signals.size());
        assertEquals(AnomalySignalType.DUPLICATE_CLAIM, signals.get(0).type());
        assertEquals(AnomalySeverity.HIGH, signals.get(0).severity());
        assertTrue(signals.get(0).detail().contains("CLM-SIBLING"), "names the duplicated claim");
        assertTrue(signals.get(0).detail().contains("99213"), "names the shared procedure");
    }

    @Test
    void does_not_flag_when_service_dates_differ_or_codes_are_disjoint() {
        Subject subject = subject(new BigDecimal("150.00"), "99213");
        OtherClaim differentDate = new OtherClaim("CLM-A", LocalDate.of(2025, 12, 1), Set.of("99213"));
        OtherClaim disjointCodes = new OtherClaim("CLM-B", NOV_1, Set.of("80053"));

        List<DetectedSignal> signals = ClaimAnomalyDetector.detect(
                subject, new Context(List.of(differentDate, disjointCodes), THRESHOLD));

        assertTrue(signals.isEmpty(), "neither sibling is a duplicate");
    }

    @Test
    void flags_a_high_total_charge_over_the_threshold_only() {
        List<DetectedSignal> over = ClaimAnomalyDetector.detect(
                subject(new BigDecimal("5000.01")), new Context(List.of(), THRESHOLD));
        assertEquals(1, over.size());
        assertEquals(AnomalySignalType.HIGH_TOTAL_CHARGE, over.get(0).type());
        assertEquals(AnomalySeverity.MEDIUM, over.get(0).severity());

        // Exactly at the threshold is not "exceeds" — no signal.
        List<DetectedSignal> atThreshold = ClaimAnomalyDetector.detect(
                subject(new BigDecimal("5000.00")), new Context(List.of(), THRESHOLD));
        assertTrue(atThreshold.isEmpty(), "a total equal to the threshold does not fire");
    }

    @Test
    void a_clean_claim_yields_no_signals() {
        List<DetectedSignal> signals = ClaimAnomalyDetector.detect(
                subject(new BigDecimal("150.00"), "99213"), new Context(List.of(), THRESHOLD));
        assertTrue(signals.isEmpty());
    }
}
