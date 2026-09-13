package com.healthcloud.patient;

import com.healthcloud.context.UserContextAccessor;
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
}
