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
     * A page of the tenant's referrals for a broad-role caller (§Phase 9), optionally filtered to one status
     * and/or a free-text search term. Both filters run in SQL ({@code null} status = any status; {@code null}
     * {@code q} = no search — else a case-insensitive "contains" match on the referral number, a PHI-free
     * identifier); ordering/paging come from the {@link Pageable}.
     */
    @Query("select r from Referral r where r.organizationId = :org "
            + "and (:status is null or r.status = :status) "
            + "and (:q is null or lower(r.referralNumber) like lower(cast(:q as string)) escape '\\')")
    Page<Referral> searchAll(UUID org, ReferralStatus status, String q, Pageable pageable);

    /**
     * A page of the tenant's referrals restricted to a set of patients (the gated-caller scoping — a provider's
     * assigned patients, or a single {@code ?patientId=}), optionally filtered to one status and/or a free-text
     * referral-number search (see {@link #searchAll}). Callers must pass a non-empty {@code patientIds} (an empty
     * accessible set is short-circuited in the service).
     */
    @Query("select r from Referral r where r.organizationId = :org "
            + "and r.patientId in :patientIds "
            + "and (:status is null or r.status = :status) "
            + "and (:q is null or lower(r.referralNumber) like lower(cast(:q as string)) escape '\\')")
    Page<Referral> searchForPatients(
            UUID org, Collection<UUID> patientIds, ReferralStatus status, String q, Pageable pageable);
}
