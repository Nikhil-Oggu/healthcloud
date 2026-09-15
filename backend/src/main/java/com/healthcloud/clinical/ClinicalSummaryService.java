package com.healthcloud.clinical;

import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.consent.ConsentPolicyService;
import com.healthcloud.consent.ConsentPurpose;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientAccessGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clinical summary reads and writes, always about a patient in the caller's tenant. Every operation passes
 * through the layered authorization pipeline (§21): tenant → function/role → object/relationship
 * ({@link PatientAccessGuard}) → consent + field-level masking (§22.5/§23). The organization id is taken from
 * the loaded patient (derived on the backend), never from the client, so a caller can only ever touch summaries
 * for patients they can already reach — an unreachable patient is a secure 404.
 *
 * <p>The diagnosis references the global medical code catalog (V14): a summary's diagnosis must be a real,
 * active ICD-10-CM code, validated here so an unknown code is a clean 400 (not a raw DB constraint error).
 */
@Service
@Transactional(readOnly = true)
public class ClinicalSummaryService {

    /** Roles allowed to author clinical summaries. A PROVIDER must also be actively assigned (the guard). */
    private static final String[] WRITE_ROLES = {"PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** Diagnosis codes are ICD-10-CM (the diagnosis vocabulary); procedures (HCPCS/CPT) belong to claims. */
    private static final CodeSystem DIAGNOSIS_SYSTEM = CodeSystem.ICD10CM;

    /** The backend-fixed purpose for reading clinical context (§21.4) — not chosen by the client. */
    private static final ConsentPurpose READ_PURPOSE = ConsentPurpose.CARE_COORDINATION;

    private final ClinicalSummaryRepository summaries;
    private final MedicalCodeRepository medicalCodes;
    private final ConsentPolicyService consentPolicy;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public ClinicalSummaryService(ClinicalSummaryRepository summaries, MedicalCodeRepository medicalCodes,
                                  ConsentPolicyService consentPolicy, PatientAccessGuard accessGuard,
                                  UserContextAccessor userContext) {
        this.summaries = summaries;
        this.medicalCodes = medicalCodes;
        this.consentPolicy = consentPolicy;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * A patient's clinical summaries (newest first), each field-masked by consent for the calling actor. The
     * patient object/relationship gate runs first — an unreachable patient is a secure 404.
     */
    public List<ClinicalSummaryDto> list(UUID patientId) {
        Patient patient = accessGuard.requireAccessibleInTenant(patientId);
        UUID actorUserId = userContext.requireUser().userId();
        return summaries
                .findByOrganizationIdAndPatientIdOrderByEncounterDateDescCreatedAtDesc(
                        patient.getOrganizationId(), patientId)
                .stream()
                .map(summary -> toFieldSafeDto(summary, actorUserId))
                .toList();
    }

    /** A single clinical summary for the patient (field-masked by consent), or a secure 404. */
    public ClinicalSummaryDto getById(UUID patientId, UUID summaryId) {
        Patient patient = accessGuard.requireAccessibleInTenant(patientId);
        ClinicalSummary summary = summaries.findByIdAndOrganizationId(summaryId, patient.getOrganizationId())
                .filter(s -> s.getPatientId().equals(patientId))
                .orElseThrow(NotFoundException::new);
        return toFieldSafeDto(summary, userContext.requireUser().userId());
    }

    /**
     * Create a clinical summary for the patient. Requires a write role (403 otherwise) AND — for a PROVIDER —
     * an active assignment to the patient (the guard, else secure 404). The diagnosis must be a real active
     * ICD-10-CM code (else 400). The response is unmasked: the caller supplied the data.
     */
    @Transactional
    public ClinicalSummaryDto create(UUID patientId, ClinicalSummaryCreateRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        Patient patient = accessGuard.requireAccessibleInTenant(patientId);
        UUID authorUserId = userContext.requireUser().userId();

        MedicalCode diagnosis = medicalCodes
                .findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(DIAGNOSIS_SYSTEM, request.diagnosisCode())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Unknown ICD-10-CM diagnosis code: " + request.diagnosisCode()));

        ClinicalSummary saved = summaries.save(new ClinicalSummary(
                patient.getOrganizationId(),
                patientId,
                request.summaryType(),
                request.encounterDate(),
                request.title(),
                diagnosis.getCodeSystem(),
                diagnosis.getCode(), // store the catalog's canonical spelling, not the caller's casing
                request.narrative(),
                authorUserId));
        return ClinicalSummaryDto.from(saved);
    }

    /**
     * Build a field-safe view (§23.3): for each consent-controlled field, the consent+purpose decision (§22.5)
     * for this actor determines whether it is returned or masked. Deny-by-default — the narrative is withheld
     * unless an applicable consent GRANT exists for CLINICAL_CONTEXT / care coordination.
     */
    private ClinicalSummaryDto toFieldSafeDto(ClinicalSummary summary, UUID actorUserId) {
        List<String> maskedFields = new ArrayList<>();
        for (ClinicalSummaryFieldPolicy field : ClinicalSummaryFieldPolicy.consentControlled()) {
            boolean granted = consentPolicy.decideForActor(
                    summary.getOrganizationId(), actorUserId, summary.getPatientId(),
                    READ_PURPOSE, field.dataCategory()).isGranted();
            if (!granted) {
                maskedFields.add(field.jsonField());
            }
        }
        return ClinicalSummaryDto.masked(summary, maskedFields);
    }
}
