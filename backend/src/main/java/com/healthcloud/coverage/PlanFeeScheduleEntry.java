package com.healthcloud.coverage;

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
 * The allowed amount a {@link CoveragePlan} recognizes for a procedure code (source-of-truth §Phase 5). Plan
 * config, tenant-owned via {@code organizationId} and NOT patient-scoped (like the plan itself). The procedure
 * FKs the global medical code catalog. Immutable once created (no {@code @Version}) — a fee-schedule entry is
 * added or removed, not edited. When the adjudication engine adjudicates a claim under the covering plan, a
 * line whose procedure has a fee-schedule entry is allowed {@code min(charge, allowedAmount)} instead of the
 * full charge (the difference is a provider write-off); a line with no entry falls back to allowed = charge.
 */
@Entity
@Table(name = "plan_fee_schedule")
public class PlanFeeScheduleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "coverage_plan_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID coveragePlanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_system", nullable = false, length = 16, updatable = false)
    private CodeSystem codeSystem;

    @Column(nullable = false, length = 16, updatable = false)
    private String code;

    @Column(name = "allowed_amount", nullable = false, updatable = false)
    private BigDecimal allowedAmount;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PlanFeeScheduleEntry() {
        // for JPA
    }

    public PlanFeeScheduleEntry(UUID organizationId, UUID coveragePlanId, CodeSystem codeSystem, String code,
                                BigDecimal allowedAmount, UUID createdBy) {
        this.organizationId = organizationId;
        this.coveragePlanId = coveragePlanId;
        this.codeSystem = codeSystem;
        this.code = code;
        this.allowedAmount = allowedAmount;
        this.createdBy = createdBy;
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

    public UUID getCoveragePlanId() {
        return coveragePlanId;
    }

    public CodeSystem getCodeSystem() {
        return codeSystem;
    }

    public String getCode() {
        return code;
    }

    public BigDecimal getAllowedAmount() {
        return allowedAmount;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
