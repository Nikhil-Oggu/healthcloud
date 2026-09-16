package com.healthcloud.appeal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure appeal state-machine policy (no Spring/DB). Covers which moves are structurally legal,
 * which roles may perform them, which are decisions, and that a reason is mandatory on every transition.
 */
class AppealTransitionsTest {

    private static final Set<String> PROVIDER = Set.of("PROVIDER");
    private static final Set<String> COORDINATOR = Set.of("CARE_COORDINATOR");
    private static final Set<String> REVIEWER = Set.of("CLAIMS_REVIEWER");
    private static final Set<String> ADMIN = Set.of("ORG_ADMIN");

    @Test
    void legal_moves_follow_the_lifecycle() {
        assertTrue(AppealTransitions.isAllowed(AppealStatus.SUBMITTED, AppealStatus.UPHELD));
        assertTrue(AppealTransitions.isAllowed(AppealStatus.SUBMITTED, AppealStatus.OVERTURNED));
        assertTrue(AppealTransitions.isAllowed(AppealStatus.SUBMITTED, AppealStatus.WITHDRAWN));

        // Terminal states have no outgoing moves.
        assertFalse(AppealTransitions.isAllowed(AppealStatus.UPHELD, AppealStatus.OVERTURNED));
        assertFalse(AppealTransitions.isAllowed(AppealStatus.OVERTURNED, AppealStatus.SUBMITTED));
        assertFalse(AppealTransitions.isAllowed(AppealStatus.WITHDRAWN, AppealStatus.UPHELD));
    }

    @Test
    void uphold_and_overturn_are_the_reviewer_roles() {
        assertTrue(AppealTransitions.isRoleAllowed(AppealStatus.SUBMITTED, AppealStatus.UPHELD, REVIEWER));
        assertTrue(AppealTransitions.isRoleAllowed(AppealStatus.SUBMITTED, AppealStatus.OVERTURNED, ADMIN));
        // A provider/coordinator does not decide an appeal (that is the claims reviewer's action).
        assertFalse(AppealTransitions.isRoleAllowed(AppealStatus.SUBMITTED, AppealStatus.UPHELD, PROVIDER));
        assertFalse(AppealTransitions.isRoleAllowed(AppealStatus.SUBMITTED, AppealStatus.OVERTURNED, COORDINATOR));
    }

    @Test
    void withdraw_is_the_submitter_roles() {
        assertTrue(AppealTransitions.isRoleAllowed(AppealStatus.SUBMITTED, AppealStatus.WITHDRAWN, PROVIDER));
        assertTrue(AppealTransitions.isRoleAllowed(AppealStatus.SUBMITTED, AppealStatus.WITHDRAWN, COORDINATOR));
        // A reviewer does not withdraw an appeal (that is the submitter's action).
        assertFalse(AppealTransitions.isRoleAllowed(AppealStatus.SUBMITTED, AppealStatus.WITHDRAWN, REVIEWER));
    }

    @Test
    void a_decision_stamps_the_decider() {
        assertTrue(AppealTransitions.isDecision(AppealStatus.UPHELD));
        assertTrue(AppealTransitions.isDecision(AppealStatus.OVERTURNED));
        assertFalse(AppealTransitions.isDecision(AppealStatus.WITHDRAWN));
    }

    @Test
    void a_reason_is_required_on_every_transition() {
        assertTrue(AppealTransitions.reasonRequired(AppealStatus.UPHELD));
        assertTrue(AppealTransitions.reasonRequired(AppealStatus.OVERTURNED));
        assertTrue(AppealTransitions.reasonRequired(AppealStatus.WITHDRAWN));
    }
}
