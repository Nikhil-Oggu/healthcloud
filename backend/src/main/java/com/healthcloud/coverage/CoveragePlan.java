package com.healthcloud.coverage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A benefit plan an organization administers (source-of-truth §Phase 4 plan/eligibility foundations).
 * Tenant-owned via {@code organizationId} but NOT patient-scoped — it is administrative benefit config, not
 * PHI. It holds the parameters the Phase-5 adjudication engine will apply: an annual {@code deductibleAmount},
 * a per-line {@code copayAmount}, a {@code coinsuranceRate} (the member's share after the deductible, 0..1),
 * and an optional {@code outOfPocketMax}. Mutable, so it carries an optimistic-lock {@code version}.
 */
@Entity
@Table(name = "coverage_plan")
public class CoveragePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "plan_code", nullable = false, length = 32, updatable = false)
    private String planCode;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 10)
    private PlanType planType;

    @Column(name = "deductible_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal deductibleAmount;

    @Column(name = "coinsurance_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal coinsuranceRate;

    @Column(name = "copay_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal copayAmount;

    @Column(name = "out_of_pocket_max", precision = 12, scale = 2)
    private BigDecimal outOfPocketMax;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected CoveragePlan() {
        // for JPA
    }

    public CoveragePlan(UUID organizationId, String planCode, String name, PlanType planType,
                        BigDecimal deductibleAmount, BigDecimal coinsuranceRate, BigDecimal copayAmount,
                        BigDecimal outOfPocketMax) {
        this.organizationId = organizationId;
        this.planCode = planCode;
        this.name = name;
        this.planType = planType;
        this.deductibleAmount = deductibleAmount;
        this.coinsuranceRate = coinsuranceRate;
        this.copayAmount = copayAmount;
        this.outOfPocketMax = outOfPocketMax;
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

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getPlanCode() {
        return planCode;
    }

    public String getName() {
        return name;
    }

    public PlanType getPlanType() {
        return planType;
    }

    public BigDecimal getDeductibleAmount() {
        return deductibleAmount;
    }

    public BigDecimal getCoinsuranceRate() {
        return coinsuranceRate;
    }

    public BigDecimal getCopayAmount() {
        return copayAmount;
    }

    public BigDecimal getOutOfPocketMax() {
        return outOfPocketMax;
    }

    public boolean isActive() {
        return active;
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
