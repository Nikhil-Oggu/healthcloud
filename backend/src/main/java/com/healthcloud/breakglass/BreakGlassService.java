package com.healthcloud.breakglass;

import com.healthcloud.audit.AuditAction;
import com.healthcloud.audit.AuditOutcome;
import com.healthcloud.audit.AuditService;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.InvalidStateTransitionException;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Break-glass emergency access (source-of-truth §Phase 7): a PROVIDER's self-service, time-boxed override of the
 * object/relationship gate. It is deliberately <b>not</b> approval-gated — the point is immediate access in an
 * emergency, made safe by being fully audited and reviewable after the fact.
 *
 * <p>Creating a grant loads the patient <b>directly</b> by tenant (not through {@code PatientAccessGuard}): the
 * whole purpose is to reach a patient the guard would 404 on, so the guard cannot be the validator here. A patient
 * in another tenant (or none) is still a secure 404 — break-glass never crosses the tenant boundary. The grant row
 * and a {@code BREAK_GLASS_INVOKED} audit event are written in one transaction (§31.6); the free-text reason is
 * kept on the grant for review and never copied into the PHI-free audit detail (rule 5).
 */
@Service
@Transactional(readOnly = true)
public class BreakGlassService {

    /** Only a PROVIDER breaks glass — the relationship-gated role. Broad roles already have access. */
    private static final String PROVIDER_ROLE = "PROVIDER";

    /** Roles that may review standing emergency access (read). */
    private static final String[] REVIEW_ROLES = {"AUDITOR", "ORG_ADMIN"};

    /** Roles that may act on it (revoke a grant) — an administrative action; an auditor is read-only. */
    private static final String[] REVOKE_ROLES = {"ORG_ADMIN"};

    private final BreakGlassGrantRepository grants;
    private final PatientRepository patients;
    private final AppUserRepository appUsers;
    private final AuditService audit;
    private final UserContextAccessor userContext;
    private final long grantDurationMinutes;

    public BreakGlassService(BreakGlassGrantRepository grants, PatientRepository patients,
                             AppUserRepository appUsers, AuditService audit, UserContextAccessor userContext,
                             @Value("${healthcloud.break-glass.grant-duration-minutes}") long grantDurationMinutes) {
        this.grants = grants;
        this.patients = patients;
        this.appUsers = appUsers;
        this.audit = audit;
        this.userContext = userContext;
        this.grantDurationMinutes = grantDurationMinutes;
    }

    /**
     * Break the glass for a patient: record a time-boxed emergency grant + a BREAK_GLASS_INVOKED audit event, in
     * one transaction. PROVIDER-gated; a cross-tenant/unknown patient is a secure 404.
     */
    @Transactional
    public BreakGlassGrantDto create(CreateBreakGlassRequest request) {
        userContext.requireAnyRole(PROVIDER_ROLE);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        // Load the patient directly by tenant — NOT via the access guard (which would 404 the very case
        // break-glass exists for). Cross-tenant / unknown → secure 404, so no existence leak across tenants.
        Patient patient = patients.findByIdAndOrganizationId(request.patientId(), organizationId)
                .orElseThrow(NotFoundException::new);

        OffsetDateTime expiresAt = OffsetDateTime.now().plus(grantDurationMinutes, ChronoUnit.MINUTES);
        BreakGlassGrant saved = grants.save(new BreakGlassGrant(
                organizationId, caller.userId(), patient.getId(), request.reason(), expiresAt));

        // §31.6 / §Phase 7: record the override in the same transaction. PHI-free detail — the grant id + expiry,
        // never the free-text reason (kept on the grant row for review).
        audit.record(AuditAction.BREAK_GLASS_INVOKED, AuditService.RESOURCE_PATIENT, patient.getId(),
                AuditOutcome.SUCCESS,
                "Break-glass emergency access invoked (grant " + saved.getId() + ", expires " + expiresAt + ")");

        return BreakGlassGrantDto.from(saved);
    }

    /** The caller's own live break-glass grants (newest first). */
    public List<BreakGlassGrantDto> listMine() {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        return grants
                .findByOrganizationIdAndAppUserIdAndExpiresAtAfterAndRevokedAtIsNullOrderByCreatedAtDesc(
                        organizationId, caller.userId(), OffsetDateTime.now())
                .stream()
                .map(BreakGlassGrantDto::from)
                .toList();
    }

    /**
     * The access-review oversight read (AUDITOR/ORG_ADMIN): every live break-glass grant in the tenant, across
     * providers, with the acting provider's name resolved. A role-gated list → a disallowed role is a flat 403.
     */
    public List<BreakGlassGrantAdminDto> listActiveForTenant() {
        userContext.requireAnyRole(REVIEW_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        return grants
                .findByOrganizationIdAndExpiresAtAfterAndRevokedAtIsNullOrderByCreatedAtDesc(
                        organizationId, OffsetDateTime.now())
                .stream()
                .map(g -> BreakGlassGrantAdminDto.from(g, providerName(g.getAppUserId())))
                .toList();
    }

    /**
     * Revoke a break-glass grant early during an access review (ORG_ADMIN). Ends access immediately (the guard's
     * live checks exclude revoked grants) and records a BREAK_GLASS_REVOKED audit event, in one transaction.
     * Tenant-scoped (another tenant's grant is a secure 404); a grant that is not live is a 409.
     */
    @Transactional
    public BreakGlassGrantAdminDto revoke(UUID grantId) {
        userContext.requireAnyRole(REVOKE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        BreakGlassGrant grant = grants.findByIdAndOrganizationId(grantId, organizationId)
                .orElseThrow(NotFoundException::new);
        if (!grant.isLive()) {
            throw new InvalidStateTransitionException("This break-glass grant is not active and cannot be revoked.");
        }

        grant.revoke(caller.userId());
        BreakGlassGrant saved = grants.saveAndFlush(grant);

        audit.record(AuditAction.BREAK_GLASS_REVOKED, AuditService.RESOURCE_PATIENT, saved.getPatientId(),
                AuditOutcome.SUCCESS, "Break-glass grant " + saved.getId() + " revoked");

        return BreakGlassGrantAdminDto.from(saved, providerName(saved.getAppUserId()));
    }

    /** Resolve a provider's display name (best-effort; the id is already validated same-tenant at grant time). */
    private String providerName(UUID appUserId) {
        return appUsers.findById(appUserId).map(u -> u.getFullName()).orElse(null);
    }
}
