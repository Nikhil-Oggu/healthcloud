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
 * Plan fee-schedule reads and administration, always scoped to the caller's tenant. Fee-schedule entries are plan
 * config (not PHI), so reads are open to any authenticated same-tenant user (no relationship gate); add/remove
 * require an ORG_ADMIN. A plan (or entry) in another tenant is reported as "not found" (secure 404), never
 * "forbidden". The priced procedure is validated against the global catalog exactly as a claim line is.
 */
@Service
@Transactional(readOnly = true)
public class PlanFeeScheduleService {

    /** Roles allowed to administer the fee schedule (reads are open to any same-tenant user). */
    private static final String[] WRITE_ROLES = {"ORG_ADMIN"};

    /** The catalog systems that classify procedures (a fee-schedule entry prices a billable procedure). */
    private static final List<CodeSystem> PROCEDURE_SYSTEMS = List.of(CodeSystem.CPT, CodeSystem.HCPCS);

    private final PlanFeeScheduleRepository feeSchedule;
    private final CoveragePlanRepository plans;
    private final MedicalCodeRepository medicalCodes;
    private final UserContextAccessor userContext;

    public PlanFeeScheduleService(PlanFeeScheduleRepository feeSchedule, CoveragePlanRepository plans,
                                  MedicalCodeRepository medicalCodes, UserContextAccessor userContext) {
        this.feeSchedule = feeSchedule;
        this.plans = plans;
        this.medicalCodes = medicalCodes;
        this.userContext = userContext;
    }

    /** A plan's fee-schedule entries in the caller's tenant (plan not in tenant → secure 404). */
    public List<PlanFeeScheduleDto> list(UUID planId) {
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);
        return feeSchedule
                .findByOrganizationIdAndCoveragePlanIdOrderByCodeSystemAscCodeAsc(organizationId, planId)
                .stream()
                .map(PlanFeeScheduleDto::from)
                .toList();
    }

    /**
     * Price a procedure on a plan (ORG_ADMIN). The procedure is validated against the catalog (unknown/
     * non-procedure → 400) and stored in its canonical spelling; a duplicate entry for the same code is a 409.
     */
    @Transactional
    public PlanFeeScheduleDto add(UUID planId, AddFeeScheduleRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        MedicalCode code = resolveProcedureCode(request.procedureCode());
        if (feeSchedule.existsByOrganizationIdAndCoveragePlanIdAndCodeSystemAndCode(
                organizationId, planId, code.getCodeSystem(), code.getCode())) {
            throw new ConflictException("That procedure is already priced on this plan.");
        }

        PlanFeeScheduleEntry saved = feeSchedule.save(new PlanFeeScheduleEntry(
                organizationId, planId, code.getCodeSystem(), code.getCode(),
                request.allowedAmount(), caller.userId()));
        return PlanFeeScheduleDto.from(saved);
    }

    /** Remove a fee-schedule entry from a plan (ORG_ADMIN); a missing/cross-tenant/other-plan entry is a 404. */
    @Transactional
    public void remove(UUID planId, UUID entryId) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        PlanFeeScheduleEntry entry = feeSchedule.findByIdAndOrganizationId(entryId, organizationId)
                .filter(e -> e.getCoveragePlanId().equals(planId))
                .orElseThrow(NotFoundException::new);
        feeSchedule.delete(entry);
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
