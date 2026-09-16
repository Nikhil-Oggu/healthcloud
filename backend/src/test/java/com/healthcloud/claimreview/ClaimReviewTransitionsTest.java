package com.healthcloud.claimreview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure claim-review state-machine policy (no Spring/DB). Covers which moves are structurally
 * legal, which roles may perform them, which are decisions, and that a reason is mandatory on every transition.
 */
class ClaimReviewTransitionsTest {

    private static final Set<String> PROVIDER = Set.of("PROVIDER");
    private static final Set<String> COORDINATOR = Set.of("CARE_COORDINATOR");
    private static final Set<String> REVIEWER = Set.of("CLAIMS_REVIEWER");
    private static final Set<String> ADMIN = Set.of("ORG_ADMIN");

    @Test
    void legal_moves_follow_the_lifecycle() {
        assertTrue(ClaimReviewTransitions.isAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.RESOLVED));
        assertTrue(ClaimReviewTransitions.isAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.CANCELLED));

        // Terminal states have no outgoing moves.
        assertFalse(ClaimReviewTransitions.isAllowed(ClaimReviewStatus.RESOLVED, ClaimReviewStatus.CANCELLED));
        assertFalse(ClaimReviewTransitions.isAllowed(ClaimReviewStatus.CANCELLED, ClaimReviewStatus.OPEN));
        assertFalse(ClaimReviewTransitions.isAllowed(ClaimReviewStatus.RESOLVED, ClaimReviewStatus.OPEN));
    }

    @Test
    void resolve_is_the_reviewer_roles() {
        assertTrue(ClaimReviewTransitions.isRoleAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.RESOLVED, REVIEWER));
        assertTrue(ClaimReviewTransitions.isRoleAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.RESOLVED, ADMIN));
        // Neither a provider nor a coordinator resolves a review (that is the claims reviewer's disposition).
        assertFalse(ClaimReviewTransitions.isRoleAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.RESOLVED, PROVIDER));
        assertFalse(
                ClaimReviewTransitions.isRoleAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.RESOLVED, COORDINATOR));
    }

    @Test
    void cancel_is_the_opener_roles() {
        assertTrue(
                ClaimReviewTransitions.isRoleAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.CANCELLED, COORDINATOR));
        assertTrue(ClaimReviewTransitions.isRoleAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.CANCELLED, REVIEWER));
        assertTrue(ClaimReviewTransitions.isRoleAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.CANCELLED, ADMIN));
        // A provider is not an opener, so cannot cancel.
        assertFalse(ClaimReviewTransitions.isRoleAllowed(ClaimReviewStatus.OPEN, ClaimReviewStatus.CANCELLED, PROVIDER));
    }

    @Test
    void a_resolve_stamps_the_resolver() {
        assertTrue(ClaimReviewTransitions.isDecision(ClaimReviewStatus.RESOLVED));
        assertFalse(ClaimReviewTransitions.isDecision(ClaimReviewStatus.CANCELLED));
    }

    @Test
    void a_reason_is_required_on_every_transition() {
        assertTrue(ClaimReviewTransitions.reasonRequired(ClaimReviewStatus.RESOLVED));
        assertTrue(ClaimReviewTransitions.reasonRequired(ClaimReviewStatus.CANCELLED));
    }
}
