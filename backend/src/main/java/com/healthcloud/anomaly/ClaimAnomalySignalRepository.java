package com.healthcloud.anomaly;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-safe repository for {@link ClaimAnomalySignal}. Every finder is constrained by {@code organizationId}
 * (no bare {@code findById} in business code), so another tenant's row is simply not found. Signals are replaced
 * wholesale on a rescan, hence the org-scoped bulk delete.
 */
public interface ClaimAnomalySignalRepository extends JpaRepository<ClaimAnomalySignal, UUID> {

    List<ClaimAnomalySignal> findByOrganizationIdAndClaimIdOrderByDetectedAtAsc(UUID organizationId, UUID claimId);

    void deleteByOrganizationIdAndClaimId(UUID organizationId, UUID claimId);
}
