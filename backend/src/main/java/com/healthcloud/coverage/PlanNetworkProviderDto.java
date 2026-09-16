package com.healthcloud.coverage;

import java.util.UUID;

/**
 * Client-facing view of a plan network provider — the provider in the plan's network, with their display name
 * resolved (minimum-necessary, §21). Plan config, not PHI; not consent-masked.
 */
public record PlanNetworkProviderDto(UUID id, UUID coveragePlanId, UUID providerUserId, String providerName) {

    public static PlanNetworkProviderDto from(PlanNetworkProvider entry, String providerName) {
        return new PlanNetworkProviderDto(
                entry.getId(),
                entry.getCoveragePlanId(),
                entry.getProviderUserId(),
                providerName);
    }
}
