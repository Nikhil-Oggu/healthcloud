package com.healthcloud.claim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure claim state-machine policy (no Spring/DB). Covers which moves are structurally legal,
 * which roles may perform them, and when a reason is mandatory.
 */
class ClaimTransitionsTest {

    private static final Set<String> PROVIDER = Set.of("PROVIDER");
    private static final Set<String> COORDINATOR = Set.of("CARE_COORDINATOR");
    private static final Set<String> REVIEWER = Set.of("CLAIMS_REVIEWER");
    private static final Set<String> ADMIN = Set.of("ORG_ADMIN");

    @Test
    void legal_moves_follow_the_lifecycle() {
        assertTrue(ClaimTransitions.isAllowed(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED));
        assertTrue(ClaimTransitions.isAllowed(ClaimStatus.DRAFT, ClaimStatus.CANCELLED));
        assertTrue(ClaimTransitions.isAllowed(ClaimStatus.SUBMITTED, ClaimStatus.ACCEPTED));
        assertTrue(ClaimTransitions.isAllowed(ClaimStatus.SUBMITTED, ClaimStatus.REJECTED));
        assertTrue(ClaimTransitions.isAllowed(ClaimStatus.ACCEPTED, ClaimStatus.ADJUDICATED));

        // Illegal jumps and moves out of terminal states.
        assertFalse(ClaimTransitions.isAllowed(ClaimStatus.DRAFT, ClaimStatus.ACCEPTED));
        assertFalse(ClaimTransitions.isAllowed(ClaimStatus.REJECTED, ClaimStatus.SUBMITTED));
        assertFalse(ClaimTransitions.isAllowed(ClaimStatus.CANCELLED, ClaimStatus.SUBMITTED));
        assertFalse(ClaimTransitions.isAllowed(ClaimStatus.ADJUDICATED, ClaimStatus.ACCEPTED));
    }

    @Test
    void submit_and_cancel_are_the_submitter_roles() {
        assertTrue(ClaimTransitions.isRoleAllowed(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, PROVIDER));
        assertTrue(ClaimTransitions.isRoleAllowed(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, COORDINATOR));
        assertTrue(ClaimTransitions.isRoleAllowed(ClaimStatus.SUBMITTED, ClaimStatus.CANCELLED, COORDINATOR));
        // A reviewer does not submit claims.
        assertFalse(ClaimTransitions.isRoleAllowed(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, REVIEWER));
    }

    @Test
    void accept_and_reject_are_the_reviewer_roles() {
        assertTrue(ClaimTransitions.isRoleAllowed(ClaimStatus.SUBMITTED, ClaimStatus.ACCEPTED, REVIEWER));
        assertTrue(ClaimTransitions.isRoleAllowed(ClaimStatus.SUBMITTED, ClaimStatus.REJECTED, REVIEWER));
        assertTrue(ClaimTransitions.isRoleAllowed(ClaimStatus.SUBMITTED, ClaimStatus.ACCEPTED, ADMIN));
        // A provider or coordinator does not adjudicate a submitted claim.
        assertFalse(ClaimTransitions.isRoleAllowed(ClaimStatus.SUBMITTED, ClaimStatus.ACCEPTED, PROVIDER));
        assertFalse(ClaimTransitions.isRoleAllowed(ClaimStatus.SUBMITTED, ClaimStatus.REJECTED, COORDINATOR));
    }

    @Test
    void a_reason_is_required_only_to_reject_or_cancel() {
        assertTrue(ClaimTransitions.reasonRequired(ClaimStatus.REJECTED));
        assertTrue(ClaimTransitions.reasonRequired(ClaimStatus.CANCELLED));
        assertFalse(ClaimTransitions.reasonRequired(ClaimStatus.SUBMITTED));
        assertFalse(ClaimTransitions.reasonRequired(ClaimStatus.ACCEPTED));
    }
}
