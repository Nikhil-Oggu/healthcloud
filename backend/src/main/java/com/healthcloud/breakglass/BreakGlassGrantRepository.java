package com.healthcloud.breakglass;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-safe repository for {@link BreakGlassGrant}. Every finder is constrained by {@code organizationId} (no
 * bare {@code findById} in business code). The "active grant" queries all take an {@code asOf} instant and match
 * {@code expiresAt > asOf}, so an expired grant grants nothing.
 */
public interface BreakGlassGrantRepository extends JpaRepository<BreakGlassGrant, UUID> {

    /** Whether the provider has a live grant for this patient (the per-patient gate check used by the guard). */
    boolean existsByOrganizationIdAndAppUserIdAndPatientIdAndExpiresAtAfter(
            UUID organizationId, UUID appUserId, UUID patientId, OffsetDateTime asOf);

    /** A provider's live grants in the tenant (the guard's list-scoping input, and the "my grants" read). */
    List<BreakGlassGrant> findByOrganizationIdAndAppUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID organizationId, UUID appUserId, OffsetDateTime asOf);
}
