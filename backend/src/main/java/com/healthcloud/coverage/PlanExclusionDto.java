package com.healthcloud.coverage;

import com.healthcloud.coding.CodeSystem;
import java.util.UUID;

/**
 * Client-facing view of a plan exclusion — the excluded procedure code. Plan config, not PHI; not consent-masked.
 */
public record PlanExclusionDto(UUID id, UUID coveragePlanId, CodeSystem codeSystem, String code) {

    public static PlanExclusionDto from(PlanExclusion exclusion) {
        return new PlanExclusionDto(
                exclusion.getId(),
                exclusion.getCoveragePlanId(),
                exclusion.getCodeSystem(),
                exclusion.getCode());
    }
}
