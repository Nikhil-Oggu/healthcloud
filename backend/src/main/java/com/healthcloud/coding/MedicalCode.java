package com.healthcloud.coding;

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
 * One entry in the medical code catalog (source-of-truth §Phase 4). Reference data, NOT tenant-owned:
 * there is no {@code organizationId} — a code is a public national standard shared by every tenant, so
 * reads are authenticated but not tenant-scoped or relationship-gated. Reference rows are immutable in the
 * app (loaded/updated by data import, not user edits), so there is no optimistic {@code @Version}.
 */
@Entity
@Table(name = "medical_code")
public class MedicalCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_system", nullable = false, length = 16)
    private CodeSystem codeSystem;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MedicalCode() {
        // for JPA
    }

    public MedicalCode(CodeSystem codeSystem, String code, String description) {
        this.codeSystem = codeSystem;
        this.code = code;
        this.description = description;
        this.active = true;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public CodeSystem getCodeSystem() {
        return codeSystem;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
