package com.healthcloud.consent;

import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientRepository;
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
    private final PatientRepository patients;
    private final UserContextAccessor userContext;

    public ConsentPolicyService(ConsentDirectiveRepository directives, PatientRepository patients,
                                UserContextAccessor userContext) {
        this.directives = directives;
        this.patients = patients;
        this.userContext = userContext;
    }

    /** The effective consent decision for the calling actor accessing {@code dataCategory} for {@code purpose}. */
    public ConsentDecisionDto decide(UUID patientId, ConsentPurpose purpose, ConsentDataCategory dataCategory) {
        UUID organizationId = userContext.requireOrganizationId();
        patients.findByIdAndOrganizationId(patientId, organizationId).orElseThrow(NotFoundException::new);
        UUID actorUserId = userContext.requireUser().userId();

        List<ConsentDirective> active = directives.findByOrganizationIdAndPatientIdAndStatusInOrderByCreatedAtAsc(
                organizationId, patientId, List.of(ConsentStatus.ACTIVE));
        ConsentDecision decision = ConsentPolicy.decide(actorUserId, purpose, dataCategory, active, LocalDate.now());
        return ConsentDecisionDto.from(patientId, purpose, dataCategory, decision);
    }
}
