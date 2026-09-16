package com.healthcloud.claim;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A claim header (source-of-truth §Phase 4). Tenant-owned via {@code organizationId} and about a
 * {@code patient} in the same organization, so access inherits the patient object/relationship gate (§21
 * layer 6). The header owns its {@link ClaimLine} children (loaded via the line repository — no JPA
 * relationship mapping, matching the codebase's flat-id aggregates). {@code totalChargeAmount} is the sum of
 * the line charges, computed at creation. Created in {@link ClaimStatus#DRAFT}; controlled transitions arrive
 * with the submission-workflow slice.
 */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "claim_number", nullable = false, length = 32, updatable = false)
    private String claimNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status = ClaimStatus.DRAFT;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "total_charge_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalChargeAmount;

    /** The PROVIDER who rendered the service (nullable; §Phase 6 provider network). Set at creation. */
    @Column(name = "rendering_provider_id", columnDefinition = "uuid", updatable = false)
    private UUID renderingProviderId;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected Claim() {
        // for JPA
    }

    /** Create a claim without a rendering provider (the common case; the field is optional, §Phase 6). */
    public Claim(UUID organizationId, UUID patientId, String claimNumber, LocalDate serviceDate,
                 BigDecimal totalChargeAmount, UUID createdBy) {
        this(organizationId, patientId, claimNumber, serviceDate, totalChargeAmount, null, createdBy);
    }

    public Claim(UUID organizationId, UUID patientId, String claimNumber, LocalDate serviceDate,
                 BigDecimal totalChargeAmount, UUID renderingProviderId, UUID createdBy) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.claimNumber = claimNumber;
        this.serviceDate = serviceDate;
        this.totalChargeAmount = totalChargeAmount;
        this.renderingProviderId = renderingProviderId;
        this.createdBy = createdBy;
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

    public UUID getPatientId() {
        return patientId;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public BigDecimal getTotalChargeAmount() {
        return totalChargeAmount;
    }

    public UUID getRenderingProviderId() {
        return renderingProviderId;
    }

    public UUID getCreatedBy() {
        return createdBy;
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
