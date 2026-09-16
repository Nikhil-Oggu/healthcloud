package com.healthcloud.referral;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /** All referrals in the tenant, newest first (broad-role work queue). */
    List<Referral> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    /** Referrals for one patient in the tenant, newest first. */
    List<Referral> findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(UUID organizationId, UUID patientId);

    /** Referrals for a set of patients in the tenant (the gated-caller list scoping), newest first. */
    List<Referral> findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc(
            UUID organizationId, Set<UUID> patientIds);
}
