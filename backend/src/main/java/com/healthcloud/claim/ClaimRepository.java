package com.healthcloud.claim;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Claim headers, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — there is no bare {@code findById} in business code — so another tenant's row is
 * simply not found (a secure 404). Reads are additionally narrowed by {@link
 * com.healthcloud.patient.PatientAccessGuard} at the service layer.
 */
public interface ClaimRepository extends JpaRepository<Claim, UUID> {

    /** One claim within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<Claim> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Whether a claim number is already taken within the tenant (for a clean number allocation). */
    boolean existsByOrganizationIdAndClaimNumber(UUID organizationId, String claimNumber);

    /** Claims for one patient in the tenant, newest first (the anomaly detector's sibling-claim context). */
    List<Claim> findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(UUID organizationId, UUID patientId);

    /**
     * A page of the tenant's claims for a broad-role caller (§Phase 9), optionally filtered to one status. The
     * status is filtered in SQL (not in memory) and {@code null} means "any status". Ordering and paging come
     * from the {@link Pageable}.
     */
    @Query("select c from Claim c where c.organizationId = :org "
            + "and (:status is null or c.status = :status)")
    Page<Claim> searchAll(UUID org, ClaimStatus status, Pageable pageable);

    /**
     * A page of the tenant's claims restricted to a set of patients (the gated-caller scoping — a provider's
     * assigned patients, or a single {@code ?patientId=}), optionally filtered to one status. Callers must pass a
     * non-empty {@code patientIds} (an empty accessible set is short-circuited to an empty page in the service).
     */
    @Query("select c from Claim c where c.organizationId = :org "
            + "and c.patientId in :patientIds "
            + "and (:status is null or c.status = :status)")
    Page<Claim> searchForPatients(
            UUID org, Collection<UUID> patientIds, ClaimStatus status, Pageable pageable);
}
