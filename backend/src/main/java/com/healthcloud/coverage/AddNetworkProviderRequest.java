package com.healthcloud.coverage;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Payload to add a provider to a coverage plan's network. The {@code providerUserId} must be an active same-tenant
 * PROVIDER (else a 400, with no existence leak of users). The plan and tenant come from the path/context, never
 * the client.
 */
public record AddNetworkProviderRequest(@NotNull UUID providerUserId) {
}
