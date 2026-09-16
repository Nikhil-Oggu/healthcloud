package com.healthcloud.reprocessing;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Payload to run a reprocessing batch. The tenant and requester are taken from the caller's context; the scope is
 * one {@code coveragePlan} that must be in the caller's tenant (else a 400). Every already-ADJUDICATED claim
 * currently on that plan is re-run through the adjudication engine. No Idempotency-Key: each POST is an
 * intentional new batch.
 */
public record CreateReprocessingBatchRequest(
        @NotNull UUID coveragePlanId) {
}
