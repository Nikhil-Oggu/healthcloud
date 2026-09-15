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
 * Plan exclusion reads and administration, always scoped to the caller's tenant. Exclusions are plan config
 * (not PHI), so reads are open to any authenticated same-tenant user (no relationship gate); add/remove require
 * an ORG_ADMIN. A plan (or exclusion) in another tenant is reported as "not found" (secure 404), never
 * "forbidden". The excluded procedure is validated against the global catalog exactly as a claim line is.
 */
@Service
@Transactional(readOnly = true)
public class PlanExclusionService {

    /** Roles allowed to administer exclusions (reads are open to any same-tenant user). */
    private static final String[] WRITE_ROLES = {"ORG_ADMIN"};

    /** The catalog systems that classify procedures (an exclusion targets a billable procedure). */
    private static final List<CodeSystem> PROCEDURE_SYSTEMS = List.of(CodeSystem.CPT, CodeSystem.HCPCS);

    private final PlanExclusionRepository exclusions;
    private final CoveragePlanRepository plans;
    private final MedicalCodeRepository medicalCodes;
    private final UserContextAccessor userContext;

    public PlanExclusionService(PlanExclusionRepository exclusions, CoveragePlanRepository plans,
                                MedicalCodeRepository medicalCodes, UserContextAccessor userContext) {
        this.exclusions = exclusions;
        this.plans = plans;
        this.medicalCodes = medicalCodes;
        this.userContext = userContext;
    }

    /** A plan's exclusions in the caller's tenant (plan not in tenant → secure 404). */
    public List<PlanExclusionDto> list(UUID planId) {
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);
        return exclusions
                .findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(organizationId, planId)
                .stream()
                .map(PlanExclusionDto::from)
                .toList();
    }

    /**
     * Exclude a procedure from a plan (ORG_ADMIN). The procedure is validated against the catalog (unknown/
     * non-procedure → 400) and stored in its canonical spelling; a duplicate exclusion is a 409.
     */
    @Transactional
    public PlanExclusionDto add(UUID planId, AddPlanExclusionRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        MedicalCode code = resolveProcedureCode(request.procedureCode());
        if (exclusions.existsByOrganizationIdAndCoveragePlanIdAndCodeSystemAndCode(
                organizationId, planId, code.getCodeSystem(), code.getCode())) {
            throw new ConflictException("That procedure is already excluded from this plan.");
        }

        PlanExclusion saved = exclusions.save(new PlanExclusion(
                organizationId, planId, code.getCodeSystem(), code.getCode(), caller.userId()));
        return PlanExclusionDto.from(saved);
    }

    /** Remove an exclusion from a plan (ORG_ADMIN); a missing/cross-tenant/other-plan exclusion is a 404. */
    @Transactional
    public void remove(UUID planId, UUID exclusionId) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        PlanExclusion exclusion = exclusions.findByIdAndOrganizationId(exclusionId, organizationId)
                .filter(e -> e.getCoveragePlanId().equals(planId))
                .orElseThrow(NotFoundException::new);
        exclusions.delete(exclusion);
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
