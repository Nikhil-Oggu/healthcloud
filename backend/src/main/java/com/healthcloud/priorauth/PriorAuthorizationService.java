package com.healthcloud.priorauth;

import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.common.PageResponse;
import com.healthcloud.common.SearchTerms;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.coverage.CoveragePlan;
import com.healthcloud.coverage.CoveragePlanRepository;
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
 * Prior-authorization reads, intake and the decision lifecycle (source-of-truth §Phase 6, advanced claims).
 * Always scoped to the caller's tenant (org derived from the loaded patient/authorization, never the client).
 * Every operation passes the layered authorization pipeline (§21): tenant → function/role → object/relationship
 * ({@link PatientAccessGuard}, via the authorization's patient). A prior auth carries only coded, claim-relevant
 * data (no clinical narrative), so there is no consent field masking here — a claims reviewer can decide it
 * without unrestricted medical context (§60).
 *
 * <p>Intake and each transition follow the one-transaction aggregate pattern (§31.6): the domain change and its
 * status-history row are written atomically. The state machine lives in the pure {@link PriorAuthTransitions}
 * policy; this service loads data and applies it.
 */
@Service
@Transactional(readOnly = true)
public class PriorAuthorizationService {

    /** Roles allowed to request a prior auth. A PROVIDER must also be actively assigned (the guard). */
    private static final String[] REQUEST_ROLES = {"PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** The catalog systems that classify procedures (a prior auth is sought for a procedure, not a diagnosis). */
    private static final List<CodeSystem> PROCEDURE_SYSTEMS = List.of(CodeSystem.CPT, CodeSystem.HCPCS);

    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final PriorAuthorizationRepository priorAuths;
    private final PriorAuthorizationStatusHistoryRepository statusHistory;
    private final CoveragePlanRepository coveragePlans;
    private final MedicalCodeRepository medicalCodes;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public PriorAuthorizationService(PriorAuthorizationRepository priorAuths,
                                     PriorAuthorizationStatusHistoryRepository statusHistory,
                                     CoveragePlanRepository coveragePlans, MedicalCodeRepository medicalCodes,
                                     PatientAccessGuard accessGuard, UserContextAccessor userContext) {
        this.priorAuths = priorAuths;
        this.statusHistory = statusHistory;
        this.coveragePlans = coveragePlans;
        this.medicalCodes = medicalCodes;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * Request a prior authorization (REQUESTED) for a patient the caller can reach. Requires a request role (403
     * otherwise) AND — for a PROVIDER — an active assignment (the guard, else secure 404). The procedure code is
     * validated against the catalog (unknown/non-procedure → 400) and the coverage plan must be in-tenant (else
     * 400). The authorization + its null → REQUESTED history row are written in one transaction.
     */
    @Transactional
    public PriorAuthorizationDto request(CreatePriorAuthorizationRequest request) {
        userContext.requireAnyRole(REQUEST_ROLES);
        UserContext caller = userContext.requireUser();
        Patient patient = accessGuard.requireAccessibleInTenant(request.patientId());
        UUID organizationId = patient.getOrganizationId();

        CoveragePlan plan = coveragePlans.findByIdAndOrganizationId(request.coveragePlanId(), organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Unknown coverage plan for this tenant."));
        MedicalCode procedure = resolveProcedureCode(request.procedureCode());
        if (request.requestedServiceTo() != null
                && request.requestedServiceTo().isBefore(request.requestedServiceFrom())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The service-window end cannot precede its start.");
        }

        PriorAuthorization saved = priorAuths.save(new PriorAuthorization(
                organizationId,
                request.patientId(),
                allocateAuthNumber(organizationId),
                plan.getId(),
                procedure.getCodeSystem().name(),
                procedure.getCode(),
                request.requestedServiceFrom(),
                request.requestedServiceTo(),
                caller.userId()));

        // §31.6: domain change + status history in one transaction. from=null marks creation.
        statusHistory.save(new PriorAuthorizationStatusHistory(
                organizationId, saved.getId(), null, PriorAuthorizationStatus.REQUESTED,
                caller.userId(), "Prior authorization requested", CorrelationId.current()));

        return PriorAuthorizationDto.from(saved, plan.getName());
    }

    /**
     * Apply a controlled prior-auth transition (approve/deny/cancel). In one transaction: gate by tenant +
     * patient, then validate — order of checks: exists → legal move → role → reason → optimistic version — then
     * update the status (stamping the reviewer on a decision) and append a history row.
     */
    @Transactional
    public PriorAuthorizationDto changeStatus(UUID authId, PriorAuthStatusChangeRequest change) {
        UserContext caller = userContext.requireUser();
        // Tenant + object/relationship gate: an unreachable authorization is a secure 404 before any state leaks.
        PriorAuthorization auth = requireAccessibleAuth(authId);

        PriorAuthorizationStatus from = auth.getStatus();
        PriorAuthorizationStatus to = change.targetStatus();

        // 1. Is this a legal move at all?
        if (!PriorAuthTransitions.isAllowed(from, to)) {
            throw new InvalidStateTransitionException(
                    "Cannot change prior authorization status from " + from + " to " + to + ".");
        }
        // 2. May this caller perform it? (§12.1 function permission)
        if (!PriorAuthTransitions.isRoleAllowed(from, to, caller.roles())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
        }
        // 3. Reason required for some transitions (deny/cancel).
        if (PriorAuthTransitions.reasonRequired(to) && (change.reason() == null || change.reason().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A reason is required to " + to + " this prior authorization.");
        }
        // 4. Optimistic locking: reject a stale caller (someone else moved it first).
        if (auth.getVersion() != change.expectedVersion()) {
            throw new ConflictException(
                    "This prior authorization was modified by someone else; reload and try again.");
        }

        // A decision (APPROVED/DENIED) stamps who/when + the reason; a cancel is a plain status change.
        if (PriorAuthTransitions.isDecision(to)) {
            auth.decide(to, caller.userId(), change.reason());
        } else {
            auth.setStatus(to);
        }
        PriorAuthorization saved = priorAuths.saveAndFlush(auth); // bump @Version; response carries the new one

        statusHistory.save(new PriorAuthorizationStatusHistory(
                auth.getOrganizationId(), auth.getId(), from, to, caller.userId(),
                change.reason(), CorrelationId.current()));

        return PriorAuthorizationDto.from(saved, planName(saved.getOrganizationId(), saved.getCoveragePlanId()));
    }

    /** One prior authorization in the caller's tenant, gated by its patient (§21 layer 6), or a secure 404. */
    public PriorAuthorizationDto getById(UUID authId) {
        PriorAuthorization auth = requireAccessibleAuth(authId);
        return PriorAuthorizationDto.from(auth, planName(auth.getOrganizationId(), auth.getCoveragePlanId()));
    }

    /** The authorization's status timeline (append-only history), tenant + relationship gated (secure 404). */
    public List<PriorAuthStatusHistoryDto> getHistory(UUID authId) {
        PriorAuthorization auth = requireAccessibleAuth(authId);
        return statusHistory
                .findByOrganizationIdAndPriorAuthorizationIdOrderByCreatedAtAsc(auth.getOrganizationId(), authId)
                .stream()
                .map(PriorAuthStatusHistoryDto::from)
                .toList();
    }

    /**
     * A page of prior authorizations in the caller's tenant (header-only), optionally filtered to one patient
     * and/or a status (§Phase 9 — server-side pagination + filtering). Gated by patient (§21 layer 6): a provider
     * sees only authorizations for patients they are actively assigned to; broad roles (coordinator/admin/reviewer)
     * see the tenant's authorizations — the reviewer's work queue. Filtering, sorting, counting and paging happen
     * in the database (the {@link Pageable}), not in memory.
     */
    public PageResponse<PriorAuthorizationSummaryDto> list(
            Optional<UUID> patientId, Optional<PriorAuthorizationStatus> status,
            Optional<String> q, Pageable pageable) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        PriorAuthorizationStatus statusFilter = status.orElse(null);
        String search = SearchTerms.likeContains(q.orElse(null)); // free-text on the auth number (§Phase 9 slice 7)

        Page<PriorAuthorization> found;
        if (patientId.isPresent()) {
            // Reuse the patient gate: an inaccessible patient (another tenant, or unassigned provider) → 404.
            accessGuard.requireAccessibleInTenant(patientId.get());
            found = priorAuths.searchForPatients(
                    organizationId, Set.of(patientId.get()), statusFilter, search, pageable);
        } else {
            Optional<Set<UUID>> accessibleIds = accessGuard.accessiblePatientIdsIfGated(caller, organizationId);
            if (accessibleIds.isPresent()) {
                Set<UUID> visible = accessibleIds.get();
                if (visible.isEmpty()) {
                    // A gated caller who can reach no patients sees an empty page (no DB round trip needed).
                    return PageResponse.empty(pageable);
                }
                found = priorAuths.searchForPatients(organizationId, visible, statusFilter, search, pageable);
            } else {
                found = priorAuths.searchAll(organizationId, statusFilter, search, pageable);
            }
        }
        return PageResponse.of(found, PriorAuthorizationSummaryDto::from);
    }

    /**
     * Load a prior authorization in the caller's tenant and confirm the caller may reach its patient (§21 layer
     * 6), else a secure 404. The single choke point for every read that names one authorization.
     */
    private PriorAuthorization requireAccessibleAuth(UUID authId) {
        UUID organizationId = userContext.requireOrganizationId();
        PriorAuthorization auth = priorAuths.findByIdAndOrganizationId(authId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(auth.getPatientId());
        return auth;
    }

    /** Resolve a caller-supplied code to an active CPT/HCPCS catalog entry, or a clean 400. */
    private MedicalCode resolveProcedureCode(String code) {
        for (CodeSystem system : PROCEDURE_SYSTEMS) {
            Optional<MedicalCode> match =
                    medicalCodes.findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(system, code);
            if (match.isPresent()) {
                return match.get();
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown procedure code (CPT/HCPCS): " + code);
    }

    /** Allocate an auth number unique within the tenant (the unique index is the backstop). */
    private String allocateAuthNumber(UUID organizationId) {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "PA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!priorAuths.existsByOrganizationIdAndAuthNumber(organizationId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("Could not allocate a unique authorization number; please retry.");
    }

    /** The plan's display name for a read, or null if it cannot be resolved (defensive; the FK guarantees it). */
    private String planName(UUID organizationId, UUID coveragePlanId) {
        return coveragePlans.findByIdAndOrganizationId(coveragePlanId, organizationId)
                .map(CoveragePlan::getName)
                .orElse(null);
    }
}
