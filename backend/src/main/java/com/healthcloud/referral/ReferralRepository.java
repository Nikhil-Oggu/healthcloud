package com.healthcloud.referral;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Referrals, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId} — no bare {@code findById} in business code — so another tenant's row is simply not
 * found (a secure 404). Reads are additionally narrowed by {@link com.healthcloud.patient.PatientAccessGuard}
 * at the service layer.
 */
public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    /** One referral within the caller's tenant (cross-tenant id → empty → secure 404). */
    Optional<Referral> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Whether a referral number is already taken within the tenant (for a clean number allocation). */
    boolean existsByOrganizationIdAndReferralNumber(UUID organizationId, String referralNumber);

    /**
     * A page of the tenant's referrals for a broad-role caller (§Phase 9), optionally filtered to one status. The
     * status is filtered in SQL ({@code null} = any status); ordering/paging come from the {@link Pageable}.
     */
    @Query("select r from Referral r where r.organizationId = :org "
            + "and (:status is null or r.status = :status)")
    Page<Referral> searchAll(UUID org, ReferralStatus status, Pageable pageable);

    /**
     * A page of the tenant's referrals restricted to a set of patients (the gated-caller scoping — a provider's
     * assigned patients, or a single {@code ?patientId=}), optionally filtered to one status. Callers must pass a
     * non-empty {@code patientIds} (an empty accessible set is short-circuited in the service).
     */
    @Query("select r from Referral r where r.organizationId = :org "
            + "and r.patientId in :patientIds "
            + "and (:status is null or r.status = :status)")
    Page<Referral> searchForPatients(
            UUID org, Collection<UUID> patientIds, ReferralStatus status, Pageable pageable);
}
