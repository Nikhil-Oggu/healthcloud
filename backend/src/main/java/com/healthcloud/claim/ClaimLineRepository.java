package com.healthcloud.claim;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Claim lines, tenant-owned and always loaded for a specific claim. Scoped by {@code organizationId} (§32.10);
 * ordered by line number so an aggregate reads back deterministically.
 */
public interface ClaimLineRepository extends JpaRepository<ClaimLine, UUID> {

    /** A claim's lines within the tenant, in line-number order. */
    List<ClaimLine> findByOrganizationIdAndClaimIdOrderByLineNumberAsc(UUID organizationId, UUID claimId);
}
