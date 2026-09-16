package com.healthcloud.adjudication;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The payload of a {@code claim.adjudicated} outbox event (source-of-truth §Phase 8). A minimum-necessary,
 * PHI-free view of the adjudication decision (rule 5): the claim it concerns, the immutable version produced, the
 * outcome, and the money split (amounts are claims/benefits data, not clinical content). Deliberately carries
 * <b>no</b> patient identifier, clinical narrative, or line detail — a consumer that needs more re-reads the claim
 * through the authorized API.
 */
public record ClaimAdjudicatedEvent(
        UUID claimId,
        String claimNumber,
        int adjudicationVersion,
        String outcome,
        BigDecimal totalPlanPaidAmount,
        BigDecimal totalMemberResponsibility) {

    static ClaimAdjudicatedEvent from(com.healthcloud.claim.Claim claim, Adjudication adjudication) {
        return new ClaimAdjudicatedEvent(
                claim.getId(),
                claim.getClaimNumber(),
                adjudication.getAdjudicationVersion(),
                adjudication.getOutcome().name(),
                adjudication.getTotalPlanPaidAmount(),
                adjudication.getTotalMemberResponsibility());
    }
}
