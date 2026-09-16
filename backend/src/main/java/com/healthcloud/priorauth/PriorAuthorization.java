package com.healthcloud.priorauth;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A prior-authorization request (source-of-truth §Phase 6). Tenant-owned via {@code organizationId} and about a
 * {@code patient} in the same organization, so access inherits the patient object/relationship gate (§21 layer
 * 6). It records a single planned {@code procedure} (a catalog code) sought under a {@code coveragePlan} for a
 * service window; a reviewer approves or denies it, stamping {@code decidedBy}/{@code decidedAt}. Created in
 * {@link PriorAuthorizationStatus#REQUESTED}; controlled transitions are driven by {@code PriorAuthTransitions}.
 * Carries only coded, claim-relevant data (no clinical narrative), so it is not consent field-masked.
 */
@Entity
@Table(name = "prior_authorization")
public class PriorAuthorization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "patient_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID patientId;

    @Column(name = "auth_number", nullable = false, length = 32, updatable = false)
    private String authNumber;

    @Column(name = "coverage_plan_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID coveragePlanId;

    @Column(name = "procedure_code_system", nullable = false, length = 16, updatable = false)
    private String procedureCodeSystem;

    @Column(name = "procedure_code", nullable = false, length = 16, updatable = false)
    private String procedureCode;

    @Column(name = "requested_service_from", nullable = false, updatable = false)
    private LocalDate requestedServiceFrom;

    @Column(name = "requested_service_to", updatable = false)
    private LocalDate requestedServiceTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PriorAuthorizationStatus status = PriorAuthorizationStatus.REQUESTED;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "decided_by", columnDefinition = "uuid")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "requested_by", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected PriorAuthorization() {
        // for JPA
    }

    public PriorAuthorization(UUID organizationId, UUID patientId, String authNumber, UUID coveragePlanId,
                              String procedureCodeSystem, String procedureCode, LocalDate requestedServiceFrom,
                              LocalDate requestedServiceTo, UUID requestedBy) {
        this.organizationId = organizationId;
        this.patientId = patientId;
        this.authNumber = authNumber;
        this.coveragePlanId = coveragePlanId;
        this.procedureCodeSystem = procedureCodeSystem;
        this.procedureCode = procedureCode;
        this.requestedServiceFrom = requestedServiceFrom;
        this.requestedServiceTo = requestedServiceTo;
        this.requestedBy = requestedBy;
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

    /** Record a reviewer's decision (APPROVED/DENIED): stamp who/when and the (optional) reason. */
    public void decide(PriorAuthorizationStatus decision, UUID decidedBy, String reason) {
        this.status = decision;
        this.decidedBy = decidedBy;
        this.decidedAt = OffsetDateTime.now();
        this.decisionReason = reason;
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

    public String getAuthNumber() {
        return authNumber;
    }

    public UUID getCoveragePlanId() {
        return coveragePlanId;
    }

    public String getProcedureCodeSystem() {
        return procedureCodeSystem;
    }

    public String getProcedureCode() {
        return procedureCode;
    }

    public LocalDate getRequestedServiceFrom() {
        return requestedServiceFrom;
    }

    public LocalDate getRequestedServiceTo() {
        return requestedServiceTo;
    }

    public PriorAuthorizationStatus getStatus() {
        return status;
    }

    public void setStatus(PriorAuthorizationStatus status) {
        this.status = status;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public UUID getRequestedBy() {
        return requestedBy;
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
