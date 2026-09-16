package com.healthcloud.claimreview;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload to open a manual review on a claim. The tenant and opener are taken from the caller's context; the
 * claim must be one the caller can reach (else a secure 404) and must not already have an open review (→ 409).
 * The patient is derived from the claim, never the client. {@code reason} (why the review is opened) is optional.
 */
public record CreateClaimReviewRequest(
        @NotNull UUID claimId,
        @Size(max = 1000) String reason) {
}
