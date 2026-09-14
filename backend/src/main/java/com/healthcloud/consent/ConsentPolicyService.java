package com.healthcloud.consent;

import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.patient.PatientAccessGuard;
import com.healthcloud.relationship.CareTeamService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates the consent + purpose decision (source-of-truth §22.5) for the CALLING actor against a patient's
 * directives. Tenant-scoped (cross-tenant patient → secure 404); the actor is always the current session's
 * user, derived on the backend — you can only ever ask "may <b>I</b> access this?". The pure §22.5 algorithm
 * lives in {@link ConsentPolicy}; this service just loads the ACTIVE directives and applies it.
 *
 * <p>Scope note (this slice): this is the CONSENT decision in isolation — the layer that will feed field-level
 * masking (§23, next slice) and the fuller §21.3 pipeline (role/relationship/business-need). Purpose is a
 * backend-validated allowlist value here (§21.4); when this is wired into real resource reads the purpose is
 * fixed by the action, not chosen by the client.
 */
@Service
@Transactional(readOnly = true)
public class ConsentPolicyService {

    private final ConsentDirectiveRepository directives;
    private final PatientAccessGuard accessGuard;
    private final CareTeamService careTeam;
    private final UserContextAccessor userContext;

    public ConsentPolicyService(ConsentDirectiveRepository directives, PatientAccessGuard accessGuard,
                                CareTeamService careTeam, UserContextAccessor userContext) {
        this.directives = directives;
        this.accessGuard = accessGuard;
        this.careTeam = careTeam;
        this.userContext = userContext;
    }

    /**
     * The effective consent decision for the calling actor accessing {@code dataCategory} for {@code purpose}.
     * Passes the object/relationship gate first (§21 layer 6): a provider not assigned to the patient gets a
     * secure 404, so this endpoint cannot be used to probe consent for a patient they cannot otherwise reach.
     */
    public ConsentDecisionDto decide(UUID patientId, ConsentPurpose purpose, ConsentDataCategory dataCategory) {
        UUID organizationId = accessGuard.requireAccessibleInTenant(patientId).getOrganizationId();
        UUID actorUserId = userContext.requireUser().userId();
        return ConsentDecisionDto.from(patientId, purpose, dataCategory,
                decideForActor(organizationId, actorUserId, patientId, purpose, dataCategory));
    }

    /**
     * The raw consent decision for an already-resolved (organization, actor, patient) — used by field-level
     * masking, where the caller has already loaded the resource in its tenant and knows the actor. Skips the
     * tenant/existence check the public {@link #decide} does.
     */
    public ConsentDecision decideForActor(UUID organizationId, UUID actorUserId, UUID patientId,
                                          ConsentPurpose purpose, ConsentDataCategory dataCategory) {
        List<ConsentDirective> active = directives.findByOrganizationIdAndPatientIdAndStatusInOrderByCreatedAtAsc(
                organizationId, patientId, List.of(ConsentStatus.ACTIVE));
        boolean actorOnCareTeam = careTeam.isOnCareTeam(organizationId, actorUserId, patientId);
        return ConsentPolicy.decide(actorUserId, purpose, dataCategory, active, LocalDate.now(), actorOnCareTeam);
    }
}
