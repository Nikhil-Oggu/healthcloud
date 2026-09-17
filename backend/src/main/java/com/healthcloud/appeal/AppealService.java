package com.healthcloud.appeal;

import com.healthcloud.adjudication.AdjudicationService;
import com.healthcloud.claim.Claim;
import com.healthcloud.claim.ClaimRepository;
import com.healthcloud.claim.ClaimStatus;
import com.healthcloud.common.PageResponse;
import com.healthcloud.common.SearchTerms;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
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
 * Appeal reads, intake and the resolution lifecycle (source-of-truth §Phase 6, advanced claims). Always scoped
 * to the caller's tenant (org derived from the loaded claim/appeal, never the client). Every operation passes the
 * layered authorization pipeline (§21): tenant → function/role → object/relationship ({@link PatientAccessGuard},
 * via the appeal's patient — an appeal is about a claim, so submit gates through the claim's patient). An appeal
 * carries only claims-domain data (a dispute rationale, no clinical narrative), so there is no consent field
 * masking here.
 *
 * <p>Intake and each transition follow the one-transaction aggregate pattern (§31.6): the domain change and its
 * status-history row are written atomically. The state machine lives in the pure {@link AppealTransitions}
 * policy; this service loads data and applies it. Mirrors {@code ReferralService}, with the twist that submit is
 * gated through a parent claim.
 *
 * <p><b>Overturn wiring (§Phase 6):</b> overturning an appeal on an ADJUDICATED claim re-runs the adjudication
 * engine — appending a new immutable adjudication version under current coverage/config — in the <b>same</b>
 * transaction as the overturn, so the two commit or roll back together. A REJECTED claim's overturn records the
 * outcome only (re-opening a rejected claim into the pipeline is a later slice).
 */
@Service
@Transactional(readOnly = true)
public class AppealService {

    /** Roles allowed to submit an appeal. A PROVIDER must also be actively assigned to the patient (the guard). */
    private static final String[] SUBMIT_ROLES = {"PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** A claim is appealable only once it has a decision to dispute. */
    private static final Set<ClaimStatus> APPEALABLE = Set.of(ClaimStatus.ADJUDICATED, ClaimStatus.REJECTED);

    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final AppealRepository appeals;
    private final AppealStatusHistoryRepository statusHistory;
    private final ClaimRepository claims;
    private final AdjudicationService adjudication;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public AppealService(AppealRepository appeals, AppealStatusHistoryRepository statusHistory,
                         ClaimRepository claims, AdjudicationService adjudication,
                         PatientAccessGuard accessGuard, UserContextAccessor userContext) {
        this.appeals = appeals;
        this.statusHistory = statusHistory;
        this.claims = claims;
        this.adjudication = adjudication;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * Submit an appeal (SUBMITTED) against a claim the caller can reach. Requires a submit role (403 otherwise)
     * AND — for a PROVIDER — an active assignment to the claim's patient (the guard, else secure 404). The claim
     * must be in an appealable state (ADJUDICATED/REJECTED → else 400) and must not already have an open appeal
     * (→ 409). The patient is taken from the claim (never the client). The appeal + its null → SUBMITTED history
     * row are written in one transaction.
     */
    @Transactional
    public AppealDto submit(CreateAppealRequest request) {
        userContext.requireAnyRole(SUBMIT_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        // Load the claim in-tenant, then gate by its patient (§21 layer 6): an unreachable claim is a secure 404.
        Claim claim = claims.findByIdAndOrganizationId(request.claimId(), organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(claim.getPatientId());

        if (!APPEALABLE.contains(claim.getStatus())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This claim is not in an appealable state (it must be adjudicated or rejected).");
        }
        if (appeals.existsByOrganizationIdAndClaimIdAndStatus(organizationId, claim.getId(), AppealStatus.SUBMITTED)) {
            throw new ConflictException("This claim already has an open appeal.");
        }

        Appeal saved = appeals.save(new Appeal(
                organizationId,
                claim.getId(),
                claim.getPatientId(),
                allocateAppealNumber(organizationId),
                request.reason().strip(),
                caller.userId()));

        // §31.6: domain change + status history in one transaction. from=null marks creation.
        statusHistory.save(new AppealStatusHistory(
                organizationId, saved.getId(), null, AppealStatus.SUBMITTED,
                caller.userId(), "Appeal submitted", CorrelationId.current()));

        return AppealDto.from(saved);
    }

    /**
     * Apply a controlled appeal transition (uphold/overturn/withdraw). In one transaction: gate by tenant +
     * patient, then validate — order of checks: exists → legal move → role → reason → optimistic version — then
     * update the status (stamping the decider on a decision) and append a history row. An OVERTURNED decision on
     * an ADJUDICATED claim additionally re-runs the adjudication engine (a new immutable version), in this same
     * transaction.
     */
    @Transactional
    public AppealDto changeStatus(UUID appealId, AppealStatusChangeRequest change) {
        UserContext caller = userContext.requireUser();
        // Tenant + object/relationship gate: an unreachable appeal is a secure 404 before any state leaks.
        Appeal appeal = requireAccessibleAppeal(appealId);

        AppealStatus from = appeal.getStatus();
        AppealStatus to = change.targetStatus();

        // 1. Is this a legal move at all?
        if (!AppealTransitions.isAllowed(from, to)) {
            throw new InvalidStateTransitionException(
                    "Cannot change appeal status from " + from + " to " + to + ".");
        }
        // 2. May this caller perform it? (§12.1 function permission)
        if (!AppealTransitions.isRoleAllowed(from, to, caller.roles())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
        }
        // 3. A reason is required on every appeal transition.
        if (AppealTransitions.reasonRequired(to) && (change.reason() == null || change.reason().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A reason is required to " + to + " this appeal.");
        }
        // 4. Optimistic locking: reject a stale caller (someone else moved it first).
        if (appeal.getVersion() != change.expectedVersion()) {
            throw new ConflictException("This appeal was modified by someone else; reload and try again.");
        }

        // A decision (UPHELD/OVERTURNED) stamps who/when + the reason; a withdrawal is a plain status change.
        if (AppealTransitions.isDecision(to)) {
            appeal.decide(to, caller.userId(), change.reason());
        } else {
            appeal.setStatus(to);
        }
        Appeal saved = appeals.saveAndFlush(appeal); // bump @Version; response carries the new one

        statusHistory.save(new AppealStatusHistory(
                appeal.getOrganizationId(), appeal.getId(), from, to, caller.userId(),
                change.reason(), CorrelationId.current()));

        // Wire the appeal outcome into the money (§Phase 6): overturning re-runs the adjudication engine on the
        // disputed claim if it is ADJUDICATED, appending a new immutable version under current coverage/config —
        // in THIS transaction, so the overturn and the re-adjudication commit or roll back together (§31.6). The
        // overturning caller is a CLAIMS_REVIEWER/ORG_ADMIN, exactly the roles the engine command requires. A
        // REJECTED claim's overturn records the outcome only — re-opening a rejected claim is a later slice.
        if (to == AppealStatus.OVERTURNED) {
            claims.findByIdAndOrganizationId(appeal.getClaimId(), appeal.getOrganizationId())
                    .filter(claim -> claim.getStatus() == ClaimStatus.ADJUDICATED)
                    .ifPresent(claim -> adjudication.adjudicate(claim.getId()));
        }

        return AppealDto.from(saved);
    }

    /** One appeal in the caller's tenant, gated by its patient (§21 layer 6), or a secure 404. */
    public AppealDto getById(UUID appealId) {
        return AppealDto.from(requireAccessibleAppeal(appealId));
    }

    /** The appeal's status timeline (append-only history), tenant + relationship gated (secure 404). */
    public List<AppealStatusHistoryDto> getHistory(UUID appealId) {
        Appeal appeal = requireAccessibleAppeal(appealId);
        return statusHistory
                .findByOrganizationIdAndAppealIdOrderByCreatedAtAsc(appeal.getOrganizationId(), appealId)
                .stream()
                .map(AppealStatusHistoryDto::from)
                .toList();
    }

    /**
     * A page of appeals in the caller's tenant (header-only), optionally filtered to one claim and/or a status
     * (§Phase 9 — server-side pagination + filtering). Gated by patient (§21 layer 6): a provider sees only appeals
     * for patients they are actively assigned to; broad roles (coordinator/admin/reviewer) see the tenant's appeals
     * — the reviewer's work queue. Filtering, sorting, counting and paging happen in the database (the
     * {@link Pageable}), not in memory.
     */
    public PageResponse<AppealSummaryDto> list(
            Optional<UUID> claimId, Optional<AppealStatus> status, Optional<String> q, Pageable pageable) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        AppealStatus statusFilter = status.orElse(null);
        String search = SearchTerms.likeContains(q.orElse(null)); // free-text on the appeal number (§Phase 9 slice 7)

        Page<Appeal> found;
        if (claimId.isPresent()) {
            // Reuse the patient gate via the claim: an inaccessible claim (another tenant, or unassigned
            // provider) → 404, so a filtered listing cannot confirm a claim the caller cannot reach.
            Claim claim = claims.findByIdAndOrganizationId(claimId.get(), organizationId)
                    .orElseThrow(NotFoundException::new);
            accessGuard.requireAccessibleInTenant(claim.getPatientId());
            found = appeals.searchForClaim(organizationId, claimId.get(), statusFilter, search, pageable);
        } else {
            Optional<Set<UUID>> accessibleIds = accessGuard.accessiblePatientIdsIfGated(caller, organizationId);
            if (accessibleIds.isPresent()) {
                Set<UUID> visible = accessibleIds.get();
                if (visible.isEmpty()) {
                    // A gated caller who can reach no patients sees an empty page (no DB round trip needed).
                    return PageResponse.empty(pageable);
                }
                found = appeals.searchForPatients(organizationId, visible, statusFilter, search, pageable);
            } else {
                found = appeals.searchAll(organizationId, statusFilter, search, pageable);
            }
        }
        return PageResponse.of(found, AppealSummaryDto::from);
    }

    /**
     * Load an appeal in the caller's tenant and confirm the caller may reach its patient (§21 layer 6), else a
     * secure 404. The single choke point for every read that names one appeal.
     */
    private Appeal requireAccessibleAppeal(UUID appealId) {
        UUID organizationId = userContext.requireOrganizationId();
        Appeal appeal = appeals.findByIdAndOrganizationId(appealId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(appeal.getPatientId());
        return appeal;
    }

    /** Allocate an appeal number unique within the tenant (the unique index is the backstop). */
    private String allocateAppealNumber(UUID organizationId) {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "APL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!appeals.existsByOrganizationIdAndAppealNumber(organizationId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("Could not allocate a unique appeal number; please retry.");
    }
}
