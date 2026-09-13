package com.healthcloud.consent;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consent directive lifecycle (source-of-truth §22). A directive is the patient's recorded decision to
 * GRANT or DENY access for a purpose + data category + scope. Activated consent is immutable (§22.4):
 * recording a change to an existing directive SUPERSEDES the current version and inserts the next one in the
 * same group; revocation flips the current row to REVOKED with immediate effect. History is never deleted.
 *
 * <p>Scope note (this slice): consent lifecycle only. The policy evaluator that consumes these rows (§21.3
 * access decision, §22.5 conflict/specificity) and field-level masking (§23) arrive in later Phase 3 slices;
 * consent-lifecycle audit events (§22.6) land with the Phase 7 audit chain. Writes are gated to staff
 * (CARE_COORDINATOR/ORG_ADMIN) recording consent on a patient's behalf — patient self-service needs a
 * patient-user↔patient-record link that does not exist yet (deferred).
 */
@Service
@Transactional(readOnly = true)
public class ConsentDirectiveService {

    /** Roles allowed to record/revoke a directive (reads are open to any same-tenant user in this slice). */
    private static final String[] WRITE_ROLES = {"CARE_COORDINATOR", "ORG_ADMIN"};

    /** The "current" statuses — a directive that is in force or scheduled to be (not terminal history). */
    private static final List<ConsentStatus> CURRENT = List.of(ConsentStatus.ACTIVE, ConsentStatus.SCHEDULED);

    private final ConsentDirectiveRepository directives;
    private final PatientRepository patients;
    private final UserContextAccessor userContext;

    public ConsentDirectiveService(ConsentDirectiveRepository directives, PatientRepository patients,
                                   UserContextAccessor userContext) {
        this.directives = directives;
        this.patients = patients;
        this.userContext = userContext;
    }

    /**
     * A patient's consent directives in the caller's tenant. By default only the current set
     * (ACTIVE + SCHEDULED); {@code includeHistory} returns every version, oldest first.
     */
    public List<ConsentDirectiveDto> list(UUID patientId, boolean includeHistory) {
        UUID organizationId = requirePatientInTenant(patientId);
        List<ConsentDirective> rows = includeHistory
                ? directives.findByOrganizationIdAndPatientIdOrderByCreatedAtAsc(organizationId, patientId)
                : directives.findByOrganizationIdAndPatientIdAndStatusInOrderByCreatedAtAsc(
                        organizationId, patientId, CURRENT);
        return rows.stream().map(ConsentDirectiveDto::from).toList();
    }

    /**
     * Record a consent directive for a patient. If a current directive already exists for the same natural
     * key (purpose + category + scope), it is superseded and this becomes the next version — all in one
     * transaction (§31.6). Requires a write role; the patient must be in the caller's tenant (else 404).
     */
    @Transactional
    public ConsentDirectiveDto record(UUID patientId, RecordConsentRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = requirePatientInTenant(patientId);

        validateScope(request.scopeType(), request.scopeRefId());
        LocalDate today = LocalDate.now();
        LocalDate effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : today;
        LocalDate effectiveTo = request.effectiveTo();
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The consent end date cannot be before its start date.");
        }
        ConsentStatus status = effectiveFrom.isAfter(today) ? ConsentStatus.SCHEDULED : ConsentStatus.ACTIVE;

        // Supersede the current directive for this natural key first (flush before the insert so the
        // partial unique index on the current set is honored within the transaction).
        Optional<ConsentDirective> superseded = findCurrentForNaturalKey(
                organizationId, patientId, request.purpose(), request.dataCategory(),
                request.scopeType(), request.scopeRefId());
        UUID groupId = superseded.map(ConsentDirective::getDirectiveGroupId).orElseGet(UUID::randomUUID);
        int version = superseded.map(d -> d.getVersion() + 1).orElse(1);
        superseded.ifPresent(current -> {
            current.supersede();
            directives.saveAndFlush(current);
        });

        ConsentDirective saved = directives.save(new ConsentDirective(
                organizationId, patientId, groupId, request.effect(), request.purpose(),
                request.dataCategory(), request.scopeType(), request.scopeRefId(),
                effectiveFrom, effectiveTo, status, version, caller.userId()));
        return ConsentDirectiveDto.from(saved);
    }

    /**
     * Revoke a specific current directive with immediate effect (§22.4). Requires a write role; the
     * directive must belong to this patient in the caller's tenant (else 404), must be current (else
     * invalid transition), and the caller's {@code expectedVersion} must match (else 409).
     */
    @Transactional
    public ConsentDirectiveDto revoke(UUID patientId, UUID directiveId, RevokeConsentRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = requirePatientInTenant(patientId);

        ConsentDirective directive = directives.findByIdAndOrganizationId(directiveId, organizationId)
                .filter(d -> d.getPatientId().equals(patientId))
                .orElseThrow(NotFoundException::new);

        if (!directive.isCurrent()) {
            throw new InvalidStateTransitionException(
                    "Only a current consent directive can be revoked (current status: " + directive.getStatus() + ").");
        }
        if (directive.getLockVersion() != request.expectedVersion()) {
            throw new ConflictException("This consent directive was modified by someone else; reload and try again.");
        }

        directive.revoke();
        return ConsentDirectiveDto.from(directives.saveAndFlush(directive));
    }

    /** The single current directive matching a natural key, if any (the unique index guarantees ≤ 1). */
    private Optional<ConsentDirective> findCurrentForNaturalKey(
            UUID organizationId, UUID patientId, ConsentPurpose purpose, ConsentDataCategory dataCategory,
            ConsentScopeType scopeType, UUID scopeRefId) {
        return directives
                .findByOrganizationIdAndPatientIdAndStatusInOrderByCreatedAtAsc(organizationId, patientId, CURRENT)
                .stream()
                .filter(d -> d.getPurpose() == purpose
                        && d.getDataCategory() == dataCategory
                        && d.getScopeType() == scopeType
                        && Objects.equals(d.getScopeRefId(), scopeRefId))
                .findFirst();
    }

    /** A PROVIDER-scoped directive must name a provider; the other scopes must not carry a reference. */
    private void validateScope(ConsentScopeType scopeType, UUID scopeRefId) {
        boolean providerScope = scopeType == ConsentScopeType.PROVIDER;
        if (providerScope && scopeRefId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A provider-scoped consent directive must name the provider (scopeRefId).");
        }
        if (!providerScope && scopeRefId != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Only a provider-scoped consent directive may carry a scopeRefId.");
        }
    }

    /** Resolve the tenant and confirm the patient is in it (else secure 404). Returns the organization id. */
    private UUID requirePatientInTenant(UUID patientId) {
        UUID organizationId = userContext.requireOrganizationId();
        patients.findByIdAndOrganizationId(patientId, organizationId).orElseThrow(NotFoundException::new);
        return organizationId;
    }
}
