package com.healthcloud.appeal;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Appeals, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's row is simply not
 * found (a secure 404). Reads are additionally narrowed by {@link com.healthcloud.patient.PatientAccessGuard}
 * at the service layer.
 */
public interface AppealRepository extends JpaRepository<Appeal, UUID> {

    /** One appeal within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<Appeal> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Whether an appeal number is already taken within the tenant (for a clean number allocation). */
    boolean existsByOrganizationIdAndAppealNumber(UUID organizationId, String appealNumber);

    /** Whether a claim already has an appeal in the given status (guards against a second open appeal). */
    boolean existsByOrganizationIdAndClaimIdAndStatus(UUID organizationId, UUID claimId, AppealStatus status);

    /**
     * A page of the tenant's appeals for a broad-role caller (§Phase 9), optionally filtered to one status and/or a
     * free-text search term. Both filters run in SQL ({@code null} status = any status; {@code null} {@code q} = no
     * search — else a case-insensitive "contains" match on the appeal number, a PHI-free identifier);
     * ordering/paging come from the {@link Pageable}.
     */
    @Query("select a from Appeal a where a.organizationId = :org "
            + "and (:status is null or a.status = :status) "
            + "and (:q is null or lower(a.appealNumber) like lower(cast(:q as string)) escape '\\')")
    Page<Appeal> searchAll(UUID org, AppealStatus status, String q, Pageable pageable);

    /**
     * A page of the tenant's appeals restricted to a set of patients (the gated-caller scoping — a provider's
     * assigned patients), optionally filtered to one status and/or a free-text appeal-number search (see
     * {@link #searchAll}). Callers must pass a non-empty {@code patientIds} (an empty accessible set is
     * short-circuited in the service).
     */
    @Query("select a from Appeal a where a.organizationId = :org "
            + "and a.patientId in :patientIds "
            + "and (:status is null or a.status = :status) "
            + "and (:q is null or lower(a.appealNumber) like lower(cast(:q as string)) escape '\\')")
    Page<Appeal> searchForPatients(
            UUID org, Collection<UUID> patientIds, AppealStatus status, String q, Pageable pageable);

    /**
     * A page of the tenant's appeals for one claim (the {@code ?claimId=} filter), optionally filtered to one
     * status and/or a free-text appeal-number search (see {@link #searchAll}). The caller has already gated the
     * claim by its patient in the service.
     */
    @Query("select a from Appeal a where a.organizationId = :org "
            + "and a.claimId = :claimId "
            + "and (:status is null or a.status = :status) "
            + "and (:q is null or lower(a.appealNumber) like lower(cast(:q as string)) escape '\\')")
    Page<Appeal> searchForClaim(UUID org, UUID claimId, AppealStatus status, String q, Pageable pageable);
}
