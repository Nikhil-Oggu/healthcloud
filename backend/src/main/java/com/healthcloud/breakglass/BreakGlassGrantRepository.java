package com.healthcloud.breakglass;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-safe repository for {@link BreakGlassGrant}. Every finder is constrained by {@code organizationId} (no
 * bare {@code findById} in business code). A grant is <b>live</b> only when it is neither expired nor revoked, so
 * the active-grant queries all match {@code expiresAt > asOf} <b>and</b> {@code revokedAt IS NULL}.
 */
public interface BreakGlassGrantRepository extends JpaRepository<BreakGlassGrant, UUID> {

    /** Whether the provider has a live grant for this patient (the per-patient gate check used by the guard). */
    boolean existsByOrganizationIdAndAppUserIdAndPatientIdAndExpiresAtAfterAndRevokedAtIsNull(
            UUID organizationId, UUID appUserId, UUID patientId, OffsetDateTime asOf);

    /** A provider's live grants in the tenant (the guard's list-scoping input, and the "my grants" read). */
    List<BreakGlassGrant> findByOrganizationIdAndAppUserIdAndExpiresAtAfterAndRevokedAtIsNullOrderByCreatedAtDesc(
            UUID organizationId, UUID appUserId, OffsetDateTime asOf);

    /** Every live grant in the tenant, across providers — the access-review oversight read (admin/auditor). */
    List<BreakGlassGrant> findByOrganizationIdAndExpiresAtAfterAndRevokedAtIsNullOrderByCreatedAtDesc(
            UUID organizationId, OffsetDateTime asOf);

    /** One grant scoped to the tenant (for revocation) — another tenant's grant is simply not found. */
    Optional<BreakGlassGrant> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Retention purge (§Phase 7): delete this tenant's grants that expired before {@code cutoff}, returning the
     * number removed. Tenant-scoped by {@code organizationId}; the cutoff is strictly in the past, so only
     * long-expired operational data is removed — a live grant is never touched. Must run inside a transaction.
     */
    long deleteByOrganizationIdAndExpiresAtBefore(UUID organizationId, OffsetDateTime cutoff);
}
