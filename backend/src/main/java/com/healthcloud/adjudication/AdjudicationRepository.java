package com.healthcloud.adjudication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Adjudications, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's row is simply not
 * found (a secure 404). Reads are additionally narrowed by {@link com.healthcloud.patient.PatientAccessGuard}
 * (via the claim) at the service layer.
 */
public interface AdjudicationRepository extends JpaRepository<Adjudication, UUID> {

    /** One adjudication within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<Adjudication> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * The current (latest-version) adjudication for a claim within the tenant, if any. A claim may have several
     * immutable versions (re-adjudication, §Phase 5); the highest version is the one in effect.
     */
    Optional<Adjudication> findFirstByOrganizationIdAndClaimIdOrderByAdjudicationVersionDesc(
            UUID organizationId, UUID claimId);

    /** All adjudication versions for a claim within the tenant, newest first (the version history). */
    List<Adjudication> findByOrganizationIdAndClaimIdOrderByAdjudicationVersionDesc(
            UUID organizationId, UUID claimId);

    /** Whether a claim has already been adjudicated in this tenant (backstops the state gate). */
    boolean existsByOrganizationIdAndClaimId(UUID organizationId, UUID claimId);

    /**
     * The distinct claim ids in the tenant that have at least one adjudication version referencing the given
     * coverage plan — the reprocessing batch's candidate set (§Phase 6). The service then keeps only claims that
     * are still ADJUDICATED and whose <i>current</i> version is on that plan before re-running them.
     */
    @Query("SELECT DISTINCT a.claimId FROM Adjudication a "
            + "WHERE a.organizationId = :organizationId AND a.coveragePlanId = :coveragePlanId")
    List<UUID> findDistinctClaimIdsByCoveragePlan(
            @Param("organizationId") UUID organizationId, @Param("coveragePlanId") UUID coveragePlanId);
}
