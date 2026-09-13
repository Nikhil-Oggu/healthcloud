package com.healthcloud.patient;

import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient reads, always scoped to the caller's tenant. The organization id comes from the
 * backend-derived {@link UserContextAccessor#requireOrganizationId()} — never from the client — so a
 * caller can only see patients in their own organization. A patient in another tenant is reported as
 * "not found" (secure 404), never as "forbidden", so existence is not leaked across tenants.
 *
 * <p>Scope note (this slice): tenant scoping only. Object-relationship checks (is this provider
 * assigned to this patient?) and consent/purpose + field masking arrive in later Phase 2 / Phase 3.
 */
@Service
@Transactional(readOnly = true)
public class PatientService {

    /** Roles allowed to create/modify patient profiles (reads are open to any same-tenant user). */
    private static final String[] WRITE_ROLES = {"CARE_COORDINATOR", "ORG_ADMIN"};

    private final PatientRepository patients;
    private final UserContextAccessor userContext;

    public PatientService(PatientRepository patients, UserContextAccessor userContext) {
        this.patients = patients;
        this.userContext = userContext;
    }

    /** A single patient in the caller's tenant, or 404 if it is not in that tenant (or doesn't exist). */
    public PatientDto getById(UUID id) {
        UUID organizationId = userContext.requireOrganizationId();
        return patients.findByIdAndOrganizationId(id, organizationId)
                .map(PatientDto::from)
                .orElseThrow(NotFoundException::new);
    }

    /** All patients in the caller's tenant. */
    public List<PatientDto> listForCurrentTenant() {
        UUID organizationId = userContext.requireOrganizationId();
        return patients.findByOrganizationIdOrderByFullNameAsc(organizationId).stream()
                .map(PatientDto::from)
                .toList();
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
