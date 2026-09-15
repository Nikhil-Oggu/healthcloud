package com.healthcloud.coverage;

import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coverage plan reads and creation, always scoped to the caller's tenant (org derived from the backend
 * context, never the client). A plan is administrative benefit config, not PHI, so reads are open to any
 * authenticated same-tenant user (no relationship gate); creation requires an ORG_ADMIN. A plan in another
 * tenant is reported as "not found" (secure 404), never "forbidden".
 */
@Service
@Transactional(readOnly = true)
public class CoveragePlanService {

    /** Roles allowed to administer coverage plans (reads are open to any same-tenant user). */
    private static final String[] WRITE_ROLES = {"ORG_ADMIN"};

    private final CoveragePlanRepository plans;
    private final UserContextAccessor userContext;

    public CoveragePlanService(CoveragePlanRepository plans, UserContextAccessor userContext) {
        this.plans = plans;
        this.userContext = userContext;
    }

    /** All coverage plans in the caller's tenant, ordered by code. */
    public List<CoveragePlanDto> list() {
        UUID organizationId = userContext.requireOrganizationId();
        return plans.findByOrganizationIdOrderByPlanCodeAsc(organizationId).stream()
                .map(CoveragePlanDto::from)
                .toList();
    }

    /** One coverage plan in the caller's tenant, or a secure 404. */
    public CoveragePlanDto getById(UUID id) {
        UUID organizationId = userContext.requireOrganizationId();
        return plans.findByIdAndOrganizationId(id, organizationId)
                .map(CoveragePlanDto::from)
                .orElseThrow(NotFoundException::new);
    }

    /**
     * Create a coverage plan in the caller's tenant. Requires ORG_ADMIN (403 otherwise). The tenant is stamped
     * from context — never from the client. A duplicate plan code within the tenant is a 409.
     */
    @Transactional
    public CoveragePlanDto create(CreateCoveragePlanRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = userContext.requireOrganizationId();

        if (plans.existsByOrganizationIdAndPlanCode(organizationId, request.planCode())) {
            throw new ConflictException("A coverage plan with that code already exists.");
        }

        CoveragePlan saved = plans.save(new CoveragePlan(
                organizationId,
                request.planCode(),
                request.name(),
                request.planType(),
                request.deductibleAmount(),
                request.coinsuranceRate(),
                request.copayAmount(),
                request.outOfPocketMax()));
        return CoveragePlanDto.from(saved);
    }
}
