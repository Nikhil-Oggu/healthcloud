package com.healthcloud.patient;

import com.healthcloud.consent.ConsentPolicyService;
import com.healthcloud.consent.ConsentPurpose;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient reads, always scoped to the caller's tenant. The organization id comes from the
 * backend-derived {@link UserContextAccessor#requireOrganizationId()} — never from the client — so a
 * caller can only see patients in their own organization. A patient in another tenant is reported as
 * "not found" (secure 404), never as "forbidden", so existence is not leaked across tenants.
 *
 * <p>Reads pass through the layered authorization pipeline (§21): tenant → object/relationship gate
 * ({@link PatientAccessGuard}: a PROVIDER reads only actively-assigned patients) → consent + field-level
 * masking (§22.5, §23). Each layer only narrows access.
 */
@Service
@Transactional(readOnly = true)
public class PatientService {

    /** Roles allowed to create/modify patient profiles (reads are open to any same-tenant user). */
    private static final String[] WRITE_ROLES = {"CARE_COORDINATOR", "ORG_ADMIN"};

    /** The backend-fixed purpose for reading a patient profile (§21.4) — not chosen by the client. */
    private static final ConsentPurpose READ_PURPOSE = ConsentPurpose.CARE_COORDINATION;

    private final PatientRepository patients;
    private final ConsentPolicyService consentPolicy;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public PatientService(PatientRepository patients, ConsentPolicyService consentPolicy,
                          PatientAccessGuard accessGuard, UserContextAccessor userContext) {
        this.patients = patients;
        this.consentPolicy = consentPolicy;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * A single patient in the caller's tenant (field-masked by consent), or a secure 404. A provider (without
     * a broad role) may read only patients they are actively assigned to (§14.3, §21 layer 6) — an unassigned
     * patient is a secure 404 (§21.5), never a 403 that would confirm the patient exists.
     */
    public PatientDto getById(UUID id) {
        Patient patient = accessGuard.requireAccessibleInTenant(id);
        return toFieldSafeDto(patient, patient.getOrganizationId(), userContext.requireUser().userId());
    }

    /**
     * Patients in the caller's tenant, each field-masked by consent. Gated callers are narrowed by the shared
     * guard: a provider sees only actively-assigned patients, a PATIENT only their own linked profile;
     * coordinators/admins (and, for now, claims reviewers) see the whole tenant.
     */
    public List<PatientDto> listForCurrentTenant() {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        List<Patient> rows = patients.findByOrganizationIdOrderByFullNameAsc(organizationId);
        Optional<Set<UUID>> accessibleIds = accessGuard.accessiblePatientIdsIfGated(caller, organizationId);
        if (accessibleIds.isPresent()) {
            Set<UUID> ids = accessibleIds.get();
            rows = rows.stream().filter(p -> ids.contains(p.getId())).toList();
        }
        return rows.stream()
                .map(patient -> toFieldSafeDto(patient, organizationId, caller.userId()))
                .toList();
    }

    /**
     * Build a field-safe patient view (§23.3): for each consent-controlled field, the consent+purpose
     * decision (§22.5) for this actor determines whether the field is returned or masked. Deny-by-default —
     * a sensitive field is withheld unless an applicable consent GRANT exists.
     */
    private PatientDto toFieldSafeDto(Patient patient, UUID organizationId, UUID actorUserId) {
        List<String> maskedFields = new ArrayList<>();
        for (PatientFieldPolicy field : PatientFieldPolicy.consentControlled()) {
            boolean granted = consentPolicy.decideForActor(
                    organizationId, actorUserId, patient.getId(), READ_PURPOSE, field.dataCategory()).isGranted();
            if (!granted) {
                maskedFields.add(field.jsonField());
            }
        }
        return PatientDto.masked(patient, maskedFields);
    }

    /**
     * Create a patient in the caller's tenant. Requires a write role (403 otherwise). The tenant is
     * stamped from context — never from the client. A duplicate MRN within the tenant is a 409.
     */
    @Transactional
    public PatientDto create(PatientCreateRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = userContext.requireOrganizationId();

        if (patients.existsByOrganizationIdAndMedicalRecordNumber(organizationId, request.medicalRecordNumber())) {
            throw new ConflictException("A patient with that medical record number already exists.");
        }

        Patient patient = patients.save(new Patient(
                organizationId,
                request.medicalRecordNumber(),
                request.fullName(),
                request.dateOfBirth()));
        return PatientDto.from(patient);
    }

    /**
     * Update a patient's mutable fields, scoped to the caller's tenant (cross-tenant → 404). Requires a
     * write role. Optimistic locking: if the caller's {@code expectedVersion} no longer matches the row,
     * a concurrent change happened → 409, and nothing is overwritten.
     */
    @Transactional
    public PatientDto update(UUID id, PatientUpdateRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = userContext.requireOrganizationId();

        Patient patient = patients.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(NotFoundException::new);

        if (patient.getVersion() != request.expectedVersion()) {
            throw new ConflictException("This patient was modified by someone else; reload and try again.");
        }

        patient.setFullName(request.fullName());
        patient.setStatus(request.status());
        // Flush now so the returned DTO carries the incremented @Version (the client's next expectedVersion).
        return PatientDto.from(patients.saveAndFlush(patient));
    }
}
