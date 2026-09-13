package com.healthcloud.request;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.CorrelationId;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-request reads and creation, always scoped to the caller's tenant (org derived from the
 * backend context, never the client). Creation follows the §31.6 one-transaction pattern: the domain
 * row and its initial status-history row are written atomically.
 *
 * <p>Scope note (this slice): create a DRAFT + read. The controlled state-machine transitions live in
 * the next slice.
 */
@Service
@Transactional(readOnly = true)
public class ServiceRequestService {

    /** Roles allowed to create a request (reviewers/auditors cannot). */
    private static final String[] CREATE_ROLES = {"PATIENT", "PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    private final ServiceRequestRepository requests;
    private final RequestStatusHistoryRepository history;
    private final PatientRepository patients;
    private final UserContextAccessor userContext;

    public ServiceRequestService(ServiceRequestRepository requests,
                                 RequestStatusHistoryRepository history,
                                 PatientRepository patients,
                                 UserContextAccessor userContext) {
        this.requests = requests;
        this.history = history;
        this.patients = patients;
        this.userContext = userContext;
    }

    /** Create a DRAFT request for a patient in the caller's tenant; records the initial history row. */
    @Transactional
    public ServiceRequestDto create(ServiceRequestCreateRequest request) {
        userContext.requireAnyRole(CREATE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        // The patient must exist in the caller's tenant. If not (incl. another tenant's id) → 404,
        // so we never leak existence and never link across tenants.
        patients.findByIdAndOrganizationId(request.patientId(), organizationId)
                .orElseThrow(NotFoundException::new);

        ServiceRequestPriority priority =
                request.priority() != null ? request.priority() : ServiceRequestPriority.NORMAL;

        ServiceRequest saved = requests.save(new ServiceRequest(
                organizationId,
                request.patientId(),
                request.type(),
                priority,
                request.title(),
                request.description(),
                caller.userId()));

        // §31.6: domain change + status history in one transaction. from=null marks creation.
        history.save(new RequestStatusHistory(
                organizationId,
                saved.getId(),
                null,
                ServiceRequestStatus.DRAFT,
                caller.userId(),
                "Request created",
                CorrelationId.current()));

        return ServiceRequestDto.from(saved);
    }

    /** One request in the caller's tenant, or 404 (also for another tenant's id). */
    public ServiceRequestDto getById(UUID id) {
        UUID organizationId = userContext.requireOrganizationId();
        return requests.findByIdAndOrganizationId(id, organizationId)
                .map(ServiceRequestDto::from)
                .orElseThrow(NotFoundException::new);
    }

    /** Requests in the caller's tenant, optionally filtered to one patient. */
    public List<ServiceRequestDto> list(Optional<UUID> patientId) {
        UUID organizationId = userContext.requireOrganizationId();
        List<ServiceRequest> found = patientId
                .map(pid -> requests.findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(organizationId, pid))
                .orElseGet(() -> requests.findByOrganizationIdOrderByCreatedAtDesc(organizationId));
        return found.stream().map(ServiceRequestDto::from).toList();
    }
}
