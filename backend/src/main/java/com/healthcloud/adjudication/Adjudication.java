package com.healthcloud.adjudication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An immutable adjudication of one claim (source-of-truth §Phase 5): which coverage plan applied and the claim
 * totals computed by the {@link AdjudicationCalculator}. Tenant-owned via {@code organizationId} and about a
 * {@code claim}, so access inherits the patient object/relationship gate (§21 layer 6). Append-only — never
 * updated once written; {@code adjudicationVersion} supports future re-adjudication (this slice writes 1 only).
 * {@code coveragePlanId}/{@code eligibilityId} are null when the outcome is {@link AdjudicationOutcome#DENIED_NO_ELIGIBILITY}.
 */
@Entity
@Table(name = "adjudication")
public class Adjudication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "claim_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID claimId;

    @Column(name = "adjudication_version", nullable = false, updatable = false)
    private int adjudicationVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private AdjudicationOutcome outcome;

    @Column(name = "coverage_plan_id", columnDefinition = "uuid", updatable = false)
    private UUID coveragePlanId;

    @Column(name = "eligibility_id", columnDefinition = "uuid", updatable = false)
    private UUID eligibilityId;

    @Column(name = "total_charge_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalChargeAmount;

    @Column(name = "total_allowed_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalAllowedAmount;

    @Column(name = "total_plan_paid_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalPlanPaidAmount;

    @Column(name = "total_member_responsibility", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalMemberResponsibility;

    @Column(name = "adjudicated_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID adjudicatedBy;

    @Column(name = "adjudicated_at", nullable = false, updatable = false)
    private OffsetDateTime adjudicatedAt;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    protected Adjudication() {
        // for JPA
    }

    public Adjudication(UUID organizationId, UUID claimId, int adjudicationVersion, AdjudicationOutcome outcome,
                        UUID coveragePlanId, UUID eligibilityId, BigDecimal totalChargeAmount,
                        BigDecimal totalAllowedAmount, BigDecimal totalPlanPaidAmount,
                        BigDecimal totalMemberResponsibility, UUID adjudicatedBy, String correlationId) {
        this.organizationId = organizationId;
        this.claimId = claimId;
        this.adjudicationVersion = adjudicationVersion;
        this.outcome = outcome;
        this.coveragePlanId = coveragePlanId;
        this.eligibilityId = eligibilityId;
        this.totalChargeAmount = totalChargeAmount;
        this.totalAllowedAmount = totalAllowedAmount;
        this.totalPlanPaidAmount = totalPlanPaidAmount;
        this.totalMemberResponsibility = totalMemberResponsibility;
        this.adjudicatedBy = adjudicatedBy;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        this.adjudicatedAt = OffsetDateTime.now();
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

    public int getAdjudicationVersion() {
        return adjudicationVersion;
    }

    public AdjudicationOutcome getOutcome() {
        return outcome;
    }

    public UUID getCoveragePlanId() {
        return coveragePlanId;
    }

    public UUID getEligibilityId() {
        return eligibilityId;
    }

    public BigDecimal getTotalChargeAmount() {
        return totalChargeAmount;
    }

    public BigDecimal getTotalAllowedAmount() {
        return totalAllowedAmount;
    }

    public BigDecimal getTotalPlanPaidAmount() {
        return totalPlanPaidAmount;
    }

    public BigDecimal getTotalMemberResponsibility() {
        return totalMemberResponsibility;
    }

    public UUID getAdjudicatedBy() {
        return adjudicatedBy;
    }

    public OffsetDateTime getAdjudicatedAt() {
        return adjudicatedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
