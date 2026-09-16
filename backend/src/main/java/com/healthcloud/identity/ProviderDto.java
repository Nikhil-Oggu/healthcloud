package com.healthcloud.identity;

import java.util.UUID;

/**
 * A same-tenant provider, for a picker (§Phase 6 provider network — the rendering-provider picker on claim
 * create). Minimum-necessary fields only (§21): just the id + display name a UI needs to let a user choose the
 * PROVIDER who rendered a service. Same shape as {@code relationship.AssignmentCandidateDto}, but a plain
 * directory entry rather than a "candidate" for an assignment.
 */
public record ProviderDto(UUID userId, String fullName) {
}
