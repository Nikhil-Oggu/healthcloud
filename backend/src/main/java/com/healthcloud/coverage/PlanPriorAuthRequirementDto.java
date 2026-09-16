package com.healthcloud.coverage;

import com.healthcloud.coding.CodeSystem;
import java.util.UUID;

/**
 * Client-facing view of a plan prior-authorization requirement — the procedure code requiring prior auth. Plan
 * config, not PHI; not consent-masked.
 */
public record PlanPriorAuthRequirementDto(UUID id, UUID coveragePlanId, CodeSystem codeSystem, String code) {

    public static PlanPriorAuthRequirementDto from(PlanPriorAuthRequirement requirement) {
        return new PlanPriorAuthRequirementDto(
                requirement.getId(),
                requirement.getCoveragePlanId(),
                requirement.getCodeSystem(),
                requirement.getCode());
    }
}
