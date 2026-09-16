package com.healthcloud.anomaly;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One advisory anomaly signal on a claim (source-of-truth §Phase 6, advanced claims). Tenant-owned via
 * {@code organizationId} and about a {@code claim} in the same organization, so access inherits the patient
 * object/relationship gate (§21 layer 6) through the claim. Carries only claims-domain data — a coded type, a
 * severity and a short human-readable detail (naming other claim numbers / procedure codes, never a patient
 * identifier) — so it is not consent field-masked.
 *
 * <p>Rows are <b>immutable</b>: a rescan replaces a claim's signals (delete + insert) rather than mutating them,
 * so there is no version column. The detector that produces them is the pure {@link ClaimAnomalyDetector}.
 */
@Entity
@Table(name = "claim_anomaly_signal")
public class ClaimAnomalySignal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "claim_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 40, updatable = false)
    private AnomalySignalType signalType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AnomalySeverity severity;

    @Column(nullable = false, length = 500, updatable = false)
    private String detail;

    @Column(name = "detected_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID detectedBy;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private OffsetDateTime detectedAt;

    protected ClaimAnomalySignal() {
        // for JPA
    }

    public ClaimAnomalySignal(UUID organizationId, UUID claimId, AnomalySignalType signalType,
                              AnomalySeverity severity, String detail, UUID detectedBy) {
        this.organizationId = organizationId;
        this.claimId = claimId;
        this.signalType = signalType;
        this.severity = severity;
        this.detail = detail;
        this.detectedBy = detectedBy;
    }

    @PrePersist
    void onCreate() {
        this.detectedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public AnomalySignalType getSignalType() {
        return signalType;
    }

    public AnomalySeverity getSeverity() {
        return severity;
    }

    public String getDetail() {
        return detail;
    }

    public UUID getDetectedBy() {
        return detectedBy;
    }

    public OffsetDateTime getDetectedAt() {
        return detectedAt;
    }
}
