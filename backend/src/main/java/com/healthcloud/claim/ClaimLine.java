package com.healthcloud.claim;

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
 * One billed line of a {@link Claim} (source-of-truth §Phase 4). Tenant-owned and FK-with-org back to its
 * claim (§32.10); the {@code procedureCodeSystem}/{@code procedureCode} point at a PROCEDURE entry (CPT/HCPCS)
 * in the global medical code catalog, validated at write time. Immutable once created (no {@code @Version} —
 * a claim's lines are set at creation), so this carries no update timestamp.
 */
@Entity
@Table(name = "claim_line")
public class ClaimLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "claim_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID claimId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "procedure_code_system", nullable = false, length = 16)
    private CodeSystem procedureCodeSystem;

    @Column(name = "procedure_code", nullable = false, length = 16)
    private String procedureCode;

    @Column(nullable = false)
    private int units;

    @Column(name = "charge_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal chargeAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ClaimLine() {
        // for JPA
    }

    public ClaimLine(UUID organizationId, UUID claimId, int lineNumber, CodeSystem procedureCodeSystem,
                     String procedureCode, int units, BigDecimal chargeAmount) {
        this.organizationId = organizationId;
        this.claimId = claimId;
        this.lineNumber = lineNumber;
        this.procedureCodeSystem = procedureCodeSystem;
        this.procedureCode = procedureCode;
        this.units = units;
        this.chargeAmount = chargeAmount;
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

    public UUID getClaimId() {
        return claimId;
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

    public int getUnits() {
        return units;
    }

    public BigDecimal getChargeAmount() {
        return chargeAmount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
