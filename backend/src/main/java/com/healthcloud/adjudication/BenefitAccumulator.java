package com.healthcloud.adjudication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A patient's cumulative spend under a coverage plan within a benefit year (source-of-truth §Phase 5, §31). It
 * is the financial accumulator the adjudication engine reads-and-updates under a PESSIMISTIC_WRITE lock so
 * concurrent adjudications for the same patient/plan/year cannot lose an update. {@code deductibleMet} carries
 * the annual deductible across claims; {@code outOfPocketMet} is tracked now and its cap enforced in a later
 * slice. Tenant-owned and about a patient (accessed only via the engine, which is patient-gated by the claim).
 */
@Entity
@Table(name = "benefit_accumulator")
public class BenefitAccumulator {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "coverage_plan_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID coveragePlanId;

    @Column(name = "benefit_year", nullable = false, updatable = false)
    private int benefitYear;

    @Column(name = "deductible_met", nullable = false, precision = 12, scale = 2)
    private BigDecimal deductibleMet = BigDecimal.ZERO;

    @Column(name = "out_of_pocket_met", nullable = false, precision = 12, scale = 2)
    private BigDecimal outOfPocketMet = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected BenefitAccumulator() {
        // for JPA
    }

    public BenefitAccumulator(UUID organizationId, UUID patientId, UUID coveragePlanId, int benefitYear) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.coveragePlanId = coveragePlanId;
        this.benefitYear = benefitYear;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    /** Record additional deductible met and out-of-pocket accrued by one adjudicated claim. */
    public void add(BigDecimal deductibleApplied, BigDecimal outOfPocket) {
        this.deductibleMet = this.deductibleMet.add(deductibleApplied);
        this.outOfPocketMet = this.outOfPocketMet.add(outOfPocket);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public UUID getCoveragePlanId() {
        return coveragePlanId;
    }

    public int getBenefitYear() {
        return benefitYear;
    }

    public BigDecimal getDeductibleMet() {
        return deductibleMet;
    }

    public BigDecimal getOutOfPocketMet() {
        return outOfPocketMet;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
