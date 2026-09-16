package com.healthcloud.anomaly;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client-facing view of an anomaly signal. Coded, claims-domain data only (type + severity + a PHI-free detail);
 * not consent field-masked. Access is controlled by the tenant + object/relationship gate (via the claim) at the
 * service layer.
 */
public record ClaimAnomalySignalDto(
        UUID id,
        UUID claimId,
        AnomalySignalType signalType,
        AnomalySeverity severity,
        String detail,
        UUID detectedBy,
        OffsetDateTime detectedAt) {

    public static ClaimAnomalySignalDto from(ClaimAnomalySignal signal) {
        return new ClaimAnomalySignalDto(
                signal.getId(),
                signal.getClaimId(),
                signal.getSignalType(),
                signal.getSeverity(),
                signal.getDetail(),
                signal.getDetectedBy(),
                signal.getDetectedAt());
    }
}
