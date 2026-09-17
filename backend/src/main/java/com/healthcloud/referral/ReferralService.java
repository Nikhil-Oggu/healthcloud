package com.healthcloud.referral;

import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.common.PageResponse;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientAccessGuard;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Referral reads, intake and the decision lifecycle (source-of-truth §Phase 6, advanced claims). Always scoped
 * to the caller's tenant (org derived from the loaded patient/referral, never the client). Every operation
 * passes the layered authorization pipeline (§21): tenant → function/role → object/relationship
 * ({@link PatientAccessGuard}, via the referral's patient). A referral carries only coded, coordination-relevant
 * data (a specialty + a diagnosis code, no clinical narrative), so there is no consent field masking here.
 *
 * <p>Intake and each transition follow the one-transaction aggregate pattern (§31.6): the domain change and its
 * status-history row are written atomically. The state machine lives in the pure {@link ReferralTransitions}
 * policy; this service loads data and applies it. Mirrors {@code PriorAuthorizationService}.
 */
@Service
@Transactional(readOnly = true)
public class ReferralService {

    /** Roles allowed to request a referral. A PROVIDER must also be actively assigned (the guard). */
    private static final String[] REQUEST_ROLES = {"PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** A referral's reason is a diagnosis (ICD-10-CM); procedures (HCPCS/CPT) belong to claims/prior auth. */
    private static final CodeSystem DIAGNOSIS_SYSTEM = CodeSystem.ICD10CM;

    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final ReferralRepository referrals;
    private final ReferralStatusHistoryRepository statusHistory;
    private final MedicalCodeRepository medicalCodes;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public ReferralService(ReferralRepository referrals, ReferralStatusHistoryRepository statusHistory,
                           MedicalCodeRepository medicalCodes, PatientAccessGuard accessGuard,
                           UserContextAccessor userContext) {
        this.referrals = referrals;
        this.statusHistory = statusHistory;
        this.medicalCodes = medicalCodes;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * Request a referral (REQUESTED) for a patient the caller can reach. Requires a request role (403 otherwise)
     * AND — for a PROVIDER — an active assignment (the guard, else secure 404). The reason code is validated
     * against the catalog (unknown/non-diagnosis → 400). The referral + its null → REQUESTED history row are
     * written in one transaction.
     */
    @Transactional
    public ReferralDto request(CreateReferralRequest request) {
        userContext.requireAnyRole(REQUEST_ROLES);
        UserContext caller = userContext.requireUser();
        Patient patient = accessGuard.requireAccessibleInTenant(request.patientId());
        UUID organizationId = patient.getOrganizationId();

        MedicalCode reason = resolveDiagnosisCode(request.reasonCode());

        Referral saved = referrals.save(new Referral(
                organizationId,
                request.patientId(),
                allocateReferralNumber(organizationId),
                request.specialty().strip(),
                reason.getCodeSystem().name(),
                reason.getCode(),
                caller.userId()));

        // §31.6: domain change + status history in one transaction. from=null marks creation.
        statusHistory.save(new ReferralStatusHistory(
                organizationId, saved.getId(), null, ReferralStatus.REQUESTED,
                caller.userId(), "Referral requested", CorrelationId.current()));

        return ReferralDto.from(saved);
    }

    /**
     * Apply a controlled referral transition (approve/deny/cancel). In one transaction: gate by tenant +
     * patient, then validate — order of checks: exists → legal move → role → reason → optimistic version — then
     * update the status (stamping the decider on a decision) and append a history row.
     */
    @Transactional
    public ReferralDto changeStatus(UUID referralId, ReferralStatusChangeRequest change) {
        UserContext caller = userContext.requireUser();
        // Tenant + object/relationship gate: an unreachable referral is a secure 404 before any state leaks.
        Referral referral = requireAccessibleReferral(referralId);

        ReferralStatus from = referral.getStatus();
        ReferralStatus to = change.targetStatus();

        // 1. Is this a legal move at all?
        if (!ReferralTransitions.isAllowed(from, to)) {
            throw new InvalidStateTransitionException(
                    "Cannot change referral status from " + from + " to " + to + ".");
        }
        // 2. May this caller perform it? (§12.1 function permission)
        if (!ReferralTransitions.isRoleAllowed(from, to, caller.roles())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
        }
        // 3. Reason required for some transitions (deny/cancel).
        if (ReferralTransitions.reasonRequired(to) && (change.reason() == null || change.reason().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A reason is required to " + to + " this referral.");
        }
        // 4. Optimistic locking: reject a stale caller (someone else moved it first).
        if (referral.getVersion() != change.expectedVersion()) {
            throw new ConflictException(
                    "This referral was modified by someone else; reload and try again.");
        }

        // A decision (APPROVED/DENIED) stamps who/when + the reason; a cancel is a plain status change.
        if (ReferralTransitions.isDecision(to)) {
            referral.decide(to, caller.userId(), change.reason());
        } else {
            referral.setStatus(to);
        }
        Referral saved = referrals.saveAndFlush(referral); // bump @Version; response carries the new one

        statusHistory.save(new ReferralStatusHistory(
                referral.getOrganizationId(), referral.getId(), from, to, caller.userId(),
                change.reason(), CorrelationId.current()));

        return ReferralDto.from(saved);
    }

    /** One referral in the caller's tenant, gated by its patient (§21 layer 6), or a secure 404. */
    public ReferralDto getById(UUID referralId) {
        return ReferralDto.from(requireAccessibleReferral(referralId));
    }

    /** The referral's status timeline (append-only history), tenant + relationship gated (secure 404). */
    public List<ReferralStatusHistoryDto> getHistory(UUID referralId) {
        Referral referral = requireAccessibleReferral(referralId);
        return statusHistory
                .findByOrganizationIdAndReferralIdOrderByCreatedAtAsc(referral.getOrganizationId(), referralId)
                .stream()
                .map(ReferralStatusHistoryDto::from)
                .toList();
    }

    /**
     * A page of referrals in the caller's tenant (header-only), optionally filtered to one patient and/or a status
     * (§Phase 9 — server-side pagination + filtering). Gated by patient (§21 layer 6): a provider sees only
     * referrals for patients they are actively assigned to; broad roles (coordinator/admin/reviewer) see the
     * tenant's referrals — the coordinator's work queue. Filtering, sorting, counting and paging happen in the
     * database (the {@link Pageable}), not in memory.
     */
    public PageResponse<ReferralSummaryDto> list(
            Optional<UUID> patientId, Optional<ReferralStatus> status, Pageable pageable) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        ReferralStatus statusFilter = status.orElse(null);

        Page<Referral> found;
        if (patientId.isPresent()) {
            // Reuse the patient gate: an inaccessible patient (another tenant, or unassigned provider) → 404.
            accessGuard.requireAccessibleInTenant(patientId.get());
            found = referrals.searchForPatients(organizationId, Set.of(patientId.get()), statusFilter, pageable);
        } else {
            Optional<Set<UUID>> accessibleIds = accessGuard.accessiblePatientIdsIfGated(caller, organizationId);
            if (accessibleIds.isPresent()) {
                Set<UUID> visible = accessibleIds.get();
                if (visible.isEmpty()) {
                    // A gated caller who can reach no patients sees an empty page (no DB round trip needed).
                    return PageResponse.empty(pageable);
                }
                found = referrals.searchForPatients(organizationId, visible, statusFilter, pageable);
            } else {
                found = referrals.searchAll(organizationId, statusFilter, pageable);
            }
        }
        return PageResponse.of(found, ReferralSummaryDto::from);
    }

    /**
     * Load a referral in the caller's tenant and confirm the caller may reach its patient (§21 layer 6), else a
     * secure 404. The single choke point for every read that names one referral.
     */
    private Referral requireAccessibleReferral(UUID referralId) {
        UUID organizationId = userContext.requireOrganizationId();
        Referral referral = referrals.findByIdAndOrganizationId(referralId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(referral.getPatientId());
        return referral;
    }

    /** Resolve a caller-supplied code to an active ICD-10-CM catalog entry, or a clean 400. */
    private MedicalCode resolveDiagnosisCode(String code) {
        return medicalCodes.findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(DIAGNOSIS_SYSTEM, code)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Unknown diagnosis code (ICD-10-CM): " + code));
    }

    /** Allocate a referral number unique within the tenant (the unique index is the backstop). */
    private String allocateReferralNumber(UUID organizationId) {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!referrals.existsByOrganizationIdAndReferralNumber(organizationId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("Could not allocate a unique referral number; please retry.");
    }
}
