package com.healthcloud.coverage;

import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan prior-authorization-requirement reads and administration, always scoped to the caller's tenant.
 * Requirements are plan config (not PHI), so reads are open to any authenticated same-tenant user (no
 * relationship gate); add/remove require an ORG_ADMIN. A plan (or requirement) in another tenant is reported as
 * "not found" (secure 404), never "forbidden". The procedure is validated against the global catalog exactly as
 * a claim line or exclusion is. Mirrors {@link PlanExclusionService}.
 */
@Service
@Transactional(readOnly = true)
public class PlanPriorAuthRequirementService {

    /** Roles allowed to administer requirements (reads are open to any same-tenant user). */
    private static final String[] WRITE_ROLES = {"ORG_ADMIN"};

    /** The catalog systems that classify procedures (a requirement targets a billable procedure). */
    private static final List<CodeSystem> PROCEDURE_SYSTEMS = List.of(CodeSystem.CPT, CodeSystem.HCPCS);

    private final PlanPriorAuthRequirementRepository requirements;
    private final CoveragePlanRepository plans;
    private final MedicalCodeRepository medicalCodes;
    private final UserContextAccessor userContext;

    public PlanPriorAuthRequirementService(PlanPriorAuthRequirementRepository requirements,
                                           CoveragePlanRepository plans, MedicalCodeRepository medicalCodes,
                                           UserContextAccessor userContext) {
        this.requirements = requirements;
        this.plans = plans;
        this.medicalCodes = medicalCodes;
        this.userContext = userContext;
    }

    /** A plan's prior-auth requirements in the caller's tenant (plan not in tenant → secure 404). */
    public List<PlanPriorAuthRequirementDto> list(UUID planId) {
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);
        return requirements
                .findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(organizationId, planId)
                .stream()
                .map(PlanPriorAuthRequirementDto::from)
                .toList();
    }

    /**
     * Mark a procedure as requiring prior auth under a plan (ORG_ADMIN). The procedure is validated against the
     * catalog (unknown/non-procedure → 400) and stored in its canonical spelling; a duplicate is a 409.
     */
    @Transactional
    public PlanPriorAuthRequirementDto add(UUID planId, AddPriorAuthRequirementRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        MedicalCode code = resolveProcedureCode(request.procedureCode());
        if (requirements.existsByOrganizationIdAndCoveragePlanIdAndCodeSystemAndCode(
                organizationId, planId, code.getCodeSystem(), code.getCode())) {
            throw new ConflictException("That procedure already requires prior authorization under this plan.");
        }

        PlanPriorAuthRequirement saved = requirements.save(new PlanPriorAuthRequirement(
                organizationId, planId, code.getCodeSystem(), code.getCode(), caller.userId()));
        return PlanPriorAuthRequirementDto.from(saved);
    }

    /** Remove a requirement from a plan (ORG_ADMIN); a missing/cross-tenant/other-plan requirement is a 404. */
    @Transactional
    public void remove(UUID planId, UUID requirementId) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        PlanPriorAuthRequirement requirement = requirements.findByIdAndOrganizationId(requirementId, organizationId)
                .filter(r -> r.getCoveragePlanId().equals(planId))
                .orElseThrow(NotFoundException::new);
        requirements.delete(requirement);
    }

    private void requirePlan(UUID planId, UUID organizationId) {
        plans.findByIdAndOrganizationId(planId, organizationId).orElseThrow(NotFoundException::new);
    }

    /** Resolve a caller-supplied code to an active CPT/HCPCS catalog entry, or a clean 400. */
    private MedicalCode resolveProcedureCode(String code) {
        for (CodeSystem system : PROCEDURE_SYSTEMS) {
            Optional<MedicalCode> match =
                    medicalCodes.findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(system, code);
            if (match.isPresent()) {
                return match.get();
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown procedure code (CPT/HCPCS): " + code);
    }
}
