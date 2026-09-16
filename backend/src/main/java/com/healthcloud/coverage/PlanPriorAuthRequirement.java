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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A procedure code that requires prior authorization under a {@link CoveragePlan} (source-of-truth §Phase 6).
 * Plan config, tenant-owned via {@code organizationId} and NOT patient-scoped (like the plan itself and
 * {@code PlanExclusion}). The procedure FKs the global medical code catalog. Immutable once created (no
 * {@code @Version}) — a requirement is added or removed, not edited. When the adjudication engine sees a covered
 * claim line whose procedure requires prior auth under the covering plan, that line is paid only if an APPROVED
 * prior authorization covers the service date; otherwise it is AUTH_REQUIRED (and its charge does not accrue to
 * the deductible or out-of-pocket max).
 */
@Entity
@Table(name = "plan_prior_auth_requirement")
public class PlanPriorAuthRequirement {

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

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PlanPriorAuthRequirement() {
        // for JPA
    }

    public PlanPriorAuthRequirement(UUID organizationId, UUID coveragePlanId, CodeSystem codeSystem, String code,
                                    UUID createdBy) {
        this.organizationId = organizationId;
        this.coveragePlanId = coveragePlanId;
        this.codeSystem = codeSystem;
        this.code = code;
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

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
