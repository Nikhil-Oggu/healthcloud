package com.healthcloud.adjudication;

import com.healthcloud.coding.CodeSystem;
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
 * The immutable per-line breakdown of an {@link Adjudication} (source-of-truth §Phase 5): a snapshot of how one
 * claim line was computed — allowed, copay, deductible applied, coinsurance, plan-paid and member responsibility.
 * The procedure code and charge are copied here so an adjudication reads back self-contained. Tenant-owned and
 * FK-with-org back to its adjudication (§32.10). Immutable once created (no {@code @Version}).
 */
@Entity
@Table(name = "adjudication_line")
public class AdjudicationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "adjudication_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID adjudicationId;

    @Column(name = "claim_line_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID claimLineId;

    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "procedure_code_system", nullable = false, length = 16, updatable = false)
    private CodeSystem procedureCodeSystem;

    @Column(name = "procedure_code", nullable = false, length = 16, updatable = false)
    private String procedureCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private LineOutcome outcome;

    @Column(name = "charge_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal chargeAmount;

    @Column(name = "allowed_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal allowedAmount;

    @Column(name = "copay_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal copayAmount;

    @Column(name = "deductible_applied_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal deductibleAppliedAmount;

    @Column(name = "coinsurance_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal coinsuranceAmount;

    @Column(name = "plan_paid_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal planPaidAmount;

    @Column(name = "member_responsibility", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal memberResponsibility;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AdjudicationLine() {
        // for JPA
    }

    public AdjudicationLine(UUID organizationId, UUID adjudicationId, UUID claimLineId, int lineNumber,
                            CodeSystem procedureCodeSystem, String procedureCode, LineOutcome outcome,
                            BigDecimal chargeAmount, BigDecimal allowedAmount, BigDecimal copayAmount,
                            BigDecimal deductibleAppliedAmount, BigDecimal coinsuranceAmount,
                            BigDecimal planPaidAmount, BigDecimal memberResponsibility) {
        this.organizationId = organizationId;
        this.adjudicationId = adjudicationId;
        this.claimLineId = claimLineId;
        this.lineNumber = lineNumber;
        this.procedureCodeSystem = procedureCodeSystem;
        this.procedureCode = procedureCode;
        this.outcome = outcome;
        this.chargeAmount = chargeAmount;
        this.allowedAmount = allowedAmount;
        this.copayAmount = copayAmount;
        this.deductibleAppliedAmount = deductibleAppliedAmount;
        this.coinsuranceAmount = coinsuranceAmount;
        this.planPaidAmount = planPaidAmount;
        this.memberResponsibility = memberResponsibility;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAdjudicationId() {
        return adjudicationId;
    }

    public UUID getClaimLineId() {
        return claimLineId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public CodeSystem getProcedureCodeSystem() {
        return procedureCodeSystem;
    }

    public String getProcedureCode() {
        return procedureCode;
    }

    public LineOutcome getOutcome() {
        return outcome;
    }

    public BigDecimal getChargeAmount() {
        return chargeAmount;
    }

    public BigDecimal getAllowedAmount() {
        return allowedAmount;
    }

    public BigDecimal getCopayAmount() {
        return copayAmount;
    }

    public BigDecimal getDeductibleAppliedAmount() {
        return deductibleAppliedAmount;
    }

    public BigDecimal getCoinsuranceAmount() {
        return coinsuranceAmount;
    }

    public BigDecimal getPlanPaidAmount() {
        return planPaidAmount;
    }

    public BigDecimal getMemberResponsibility() {
        return memberResponsibility;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
