package com.healthcloud.adjudication;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Adjudications, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's row is simply not
 * found (a secure 404). Reads are additionally narrowed by {@link com.healthcloud.patient.PatientAccessGuard}
 * (via the claim) at the service layer.
 */
public interface AdjudicationRepository extends JpaRepository<Adjudication, UUID> {

    /** One adjudication within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<Adjudication> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The adjudication for a claim within the tenant, if any (a claim is adjudicated once this slice). */
    Optional<Adjudication> findByOrganizationIdAndClaimId(UUID organizationId, UUID claimId);

    /** Whether a claim has already been adjudicated in this tenant (backstops the state gate). */
    boolean existsByOrganizationIdAndClaimId(UUID organizationId, UUID claimId);
}
