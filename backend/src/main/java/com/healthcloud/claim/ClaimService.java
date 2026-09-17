package com.healthcloud.claim;

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
import com.healthcloud.identity.MembershipStatus;
import com.healthcloud.identity.OrganizationMembership;
import com.healthcloud.identity.OrganizationMembershipRepository;
import com.healthcloud.identity.UserRole;
import com.healthcloud.identity.UserRoleRepository;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientAccessGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim reads and creation, always scoped to the caller's tenant (org derived from the loaded patient, never
 * the client). Every operation passes the layered authorization pipeline (§21): tenant → function/role →
 * object/relationship ({@link PatientAccessGuard}). A claim carries only coded, claim-relevant data (no
 * clinical narrative), so there is no consent field masking here — a claims reviewer can work claims without
 * unrestricted medical context (§60), which lives (consent-masked) in the clinical summary instead.
 *
 * <p>Creation follows the one-transaction aggregate pattern (§31.6): the header and all its lines are written
 * atomically, with the header total computed from the lines. Each line's procedure must be a real active
 * PROCEDURE code (CPT/HCPCS) in the global catalog — validated here so an unknown or non-procedure code is a
 * clean 400. Controlled status transitions + status history arrive in the submission-workflow slice.
 */
@Service
@Transactional(readOnly = true)
public class ClaimService {

    /** Roles allowed to create a claim. A PROVIDER must also be actively assigned (the guard). */
    private static final String[] CREATE_ROLES = {"PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** The catalog systems that classify procedures (a claim line bills a procedure, not a diagnosis). */
    private static final List<CodeSystem> PROCEDURE_SYSTEMS = List.of(CodeSystem.CPT, CodeSystem.HCPCS);

    /** The role a rendering provider must hold (§Phase 6 provider network). */
    private static final String PROVIDER_ROLE = "PROVIDER";

    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final ClaimRepository claims;
    private final ClaimLineRepository claimLines;
    private final ClaimStatusHistoryRepository statusHistory;
    private final MedicalCodeRepository medicalCodes;
    private final OrganizationMembershipRepository memberships;
    private final UserRoleRepository userRoles;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public ClaimService(ClaimRepository claims, ClaimLineRepository claimLines,
                        ClaimStatusHistoryRepository statusHistory, MedicalCodeRepository medicalCodes,
                        OrganizationMembershipRepository memberships, UserRoleRepository userRoles,
                        PatientAccessGuard accessGuard, UserContextAccessor userContext) {
        this.claims = claims;
        this.claimLines = claimLines;
        this.statusHistory = statusHistory;
        this.medicalCodes = medicalCodes;
        this.memberships = memberships;
        this.userRoles = userRoles;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * Create a DRAFT claim (header + lines) for a patient the caller can reach. Requires a create role (403
     * otherwise) AND — for a PROVIDER — an active assignment (the guard, else secure 404). Each procedure code
     * is validated against the catalog (unknown/non-procedure → 400); the header total is the sum of the line
     * charges. The whole aggregate is written in one transaction.
     */
    @Transactional
    public ClaimDto create(CreateClaimRequest request) {
        userContext.requireAnyRole(CREATE_ROLES);
        UserContext caller = userContext.requireUser();
        Patient patient = accessGuard.requireAccessibleInTenant(request.patientId());
        UUID organizationId = patient.getOrganizationId();

        // A rendering provider, when supplied, must be an active same-tenant PROVIDER (§Phase 6) — else 400.
        if (request.renderingProviderId() != null) {
            requireSameTenantProvider(organizationId, request.renderingProviderId());
        }

        // Resolve + validate every procedure code first, so a bad line fails the whole create cleanly (400).
        List<MedicalCode> resolved = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CreateClaimLineRequest line : request.lines()) {
            resolved.add(resolveProcedureCode(line.procedureCode()));
            total = total.add(line.chargeAmount());
        }

        Claim saved = claims.save(new Claim(
                organizationId,
                request.patientId(),
                allocateClaimNumber(organizationId),
                request.serviceDate(),
                total,
                request.renderingProviderId(),
                caller.userId()));

        List<ClaimLine> lines = new ArrayList<>();
        int lineNumber = 1;
        for (int i = 0; i < request.lines().size(); i++) {
            CreateClaimLineRequest line = request.lines().get(i);
            MedicalCode code = resolved.get(i);
            int units = line.units() != null ? line.units() : 1;
            lines.add(claimLines.save(new ClaimLine(
                    organizationId, saved.getId(), lineNumber++,
                    code.getCodeSystem(), code.getCode(), units, line.chargeAmount())));
        }

        // §31.6: domain change + status history in one transaction. from=null marks creation.
        statusHistory.save(new ClaimStatusHistory(
                organizationId, saved.getId(), null, ClaimStatus.DRAFT,
                caller.userId(), "Claim created", CorrelationId.current()));

        return ClaimDto.from(saved, lines);
    }

    /**
     * Apply a controlled claim status transition (§Phase 4 submission/validation). In one transaction: gate by
     * tenant + patient, then validate — order of checks: exists → reserved-status → legal move → role → reason
     * → (submit) claim validation → optimistic version — then update the status and append a history row.
     */
    @Transactional
    public ClaimDto changeStatus(UUID claimId, ClaimStatusChangeRequest change) {
        UserContext caller = userContext.requireUser();
        // Tenant + object/relationship gate: an unreachable claim is a secure 404 before any state is revealed.
        Claim claim = requireAccessibleClaim(claimId);

        ClaimStatus from = claim.getStatus();
        ClaimStatus to = change.targetStatus();

        // ADJUDICATED is reached only by the Phase-5 adjudication engine, never a bare status change.
        if (to == ClaimStatus.ADJUDICATED) {
            throw new InvalidStateTransitionException(
                    "A claim is adjudicated by the adjudication engine, not a status change.");
        }
        // 1. Is this a legal move at all?
        if (!ClaimTransitions.isAllowed(from, to)) {
            throw new InvalidStateTransitionException(
                    "Cannot change claim status from " + from + " to " + to + ".");
        }
        // 2. May this caller perform it? (§12.1 function permission)
        if (!ClaimTransitions.isRoleAllowed(from, to, caller.roles())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
        }
        // 3. Reason required for some transitions (reject/cancel).
        if (ClaimTransitions.reasonRequired(to) && (change.reason() == null || change.reason().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A reason is required to " + to + " this claim.");
        }
        // 4. Submission validation: a claim must be well-formed to be submitted.
        if (to == ClaimStatus.SUBMITTED) {
            validateSubmittable(claim);
        }
        // 5. Optimistic locking: reject a stale caller (someone else moved the claim first).
        if (claim.getVersion() != change.expectedVersion()) {
            throw new ConflictException("This claim was modified by someone else; reload and try again.");
        }

        claim.setStatus(to);
        Claim saved = claims.saveAndFlush(claim); // bump @Version; response carries the new one

        statusHistory.save(new ClaimStatusHistory(
                claim.getOrganizationId(), claim.getId(), from, to, caller.userId(),
                change.reason(), CorrelationId.current()));

        return ClaimDto.from(saved, claimLines
                .findByOrganizationIdAndClaimIdOrderByLineNumberAsc(claim.getOrganizationId(), claim.getId()));
    }

    /** The claim's status timeline (append-only history), tenant + relationship gated (secure 404). */
    public List<ClaimStatusHistoryDto> getHistory(UUID claimId) {
        Claim claim = requireAccessibleClaim(claimId);
        return statusHistory
                .findByOrganizationIdAndClaimIdOrderByCreatedAtAsc(claim.getOrganizationId(), claimId)
                .stream()
                .map(ClaimStatusHistoryDto::from)
                .toList();
    }

    /** A claim must have at least one line and a positive total to be submitted (§Phase 4 validation). */
    private void validateSubmittable(Claim claim) {
        List<ClaimLine> lines = claimLines
                .findByOrganizationIdAndClaimIdOrderByLineNumberAsc(claim.getOrganizationId(), claim.getId());
        if (lines.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A claim must have at least one line to submit.");
        }
        if (claim.getTotalChargeAmount().signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A claim's total charge must be greater than zero to submit.");
        }
    }

    /** One claim (header + lines) in the caller's tenant, gated by its patient (§21 layer 6), or a secure 404. */
    public ClaimDto getById(UUID claimId) {
        Claim claim = requireAccessibleClaim(claimId);
        return ClaimDto.from(claim, claimLines
                .findByOrganizationIdAndClaimIdOrderByLineNumberAsc(claim.getOrganizationId(), claimId));
    }

    /**
     * A page of claims in the caller's tenant (header-only), optionally filtered to one patient and/or a status
     * (§Phase 9 — server-side pagination + filtering). The authorization is unchanged from the unpaged list: a
     * claim is gated by its patient (§21 layer 6), so a provider sees only claims for patients they are actively
     * assigned to, while broad roles (coordinator/admin/reviewer) see the tenant's claims — the reviewer's work
     * queue. The difference is that filtering, sorting, counting and paging now happen in the database (the
     * {@link Pageable}), not in memory.
     */
    public PageResponse<ClaimSummaryDto> list(
            Optional<UUID> patientId, Optional<ClaimStatus> status, Pageable pageable) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        ClaimStatus statusFilter = status.orElse(null);

        Page<Claim> found;
        if (patientId.isPresent()) {
            // Reuse the patient gate: an inaccessible patient (another tenant, or unassigned provider) → 404.
            accessGuard.requireAccessibleInTenant(patientId.get());
            found = claims.searchForPatients(organizationId, Set.of(patientId.get()), statusFilter, pageable);
        } else {
            Optional<Set<UUID>> accessibleIds = accessGuard.accessiblePatientIdsIfGated(caller, organizationId);
            if (accessibleIds.isPresent()) {
                Set<UUID> visible = accessibleIds.get();
                if (visible.isEmpty()) {
                    // A gated caller who can reach no patients sees an empty page (no DB round trip needed).
                    return PageResponse.empty(pageable);
                }
                found = claims.searchForPatients(organizationId, visible, statusFilter, pageable);
            } else {
                found = claims.searchAll(organizationId, statusFilter, pageable);
            }
        }
        return PageResponse.of(found, ClaimSummaryDto::from);
    }

    /**
     * Load a claim in the caller's tenant and confirm the caller may reach its patient (§21 layer 6), else a
     * secure 404. The single choke point for every read that names one claim.
     */
    private Claim requireAccessibleClaim(UUID claimId) {
        UUID organizationId = userContext.requireOrganizationId();
        Claim claim = claims.findByIdAndOrganizationId(claimId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(claim.getPatientId());
        return claim;
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

    /**
     * A rendering provider must be an active same-tenant PROVIDER, else 400 (no existence leak of users).
     * (Same pattern as {@code ProviderPatientAssignmentService} / {@code PlanNetworkProviderService}; a future
     * cleanup can extract this into a shared provider validator.)
     */
    private void requireSameTenantProvider(UUID organizationId, UUID providerUserId) {
        OrganizationMembership membership = memberships
                .findByOrganization_IdAndAppUser_Id(organizationId, providerUserId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "The selected rendering provider is not valid for this claim."));
        boolean isProvider = userRoles.findByMembership_Id(membership.getId()).stream()
                .map(UserRole::getRole)
                .anyMatch(r -> PROVIDER_ROLE.equals(r.getCode()));
        if (!isProvider) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The selected rendering provider is not valid for this claim.");
        }
    }

    /** Allocate a claim number unique within the tenant (the unique index is the backstop). */
    private String allocateClaimNumber(UUID organizationId) {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!claims.existsByOrganizationIdAndClaimNumber(organizationId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("Could not allocate a unique claim number; please retry.");
    }
}
