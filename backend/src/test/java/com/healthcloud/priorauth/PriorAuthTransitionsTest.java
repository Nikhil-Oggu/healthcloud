package com.healthcloud.priorauth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure prior-authorization state-machine policy (no Spring/DB). Covers which moves are
 * structurally legal, which roles may perform them, which are decisions, and when a reason is mandatory.
 */
class PriorAuthTransitionsTest {

    private static final Set<String> PROVIDER = Set.of("PROVIDER");
    private static final Set<String> COORDINATOR = Set.of("CARE_COORDINATOR");
    private static final Set<String> REVIEWER = Set.of("CLAIMS_REVIEWER");
    private static final Set<String> ADMIN = Set.of("ORG_ADMIN");

    @Test
    void legal_moves_follow_the_lifecycle() {
        assertTrue(PriorAuthTransitions.isAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.APPROVED));
        assertTrue(PriorAuthTransitions.isAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.DENIED));
        assertTrue(PriorAuthTransitions.isAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.CANCELLED));

        // Terminal states have no outgoing moves.
        assertFalse(PriorAuthTransitions.isAllowed(
                PriorAuthorizationStatus.APPROVED, PriorAuthorizationStatus.DENIED));
        assertFalse(PriorAuthTransitions.isAllowed(
                PriorAuthorizationStatus.DENIED, PriorAuthorizationStatus.REQUESTED));
        assertFalse(PriorAuthTransitions.isAllowed(
                PriorAuthorizationStatus.CANCELLED, PriorAuthorizationStatus.APPROVED));
    }

    @Test
    void approve_and_deny_are_the_reviewer_roles() {
        assertTrue(PriorAuthTransitions.isRoleAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.APPROVED, REVIEWER));
        assertTrue(PriorAuthTransitions.isRoleAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.DENIED, ADMIN));
        // A provider or coordinator does not decide an authorization.
        assertFalse(PriorAuthTransitions.isRoleAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.APPROVED, PROVIDER));
        assertFalse(PriorAuthTransitions.isRoleAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.DENIED, COORDINATOR));
    }

    @Test
    void cancel_is_the_requester_roles() {
        assertTrue(PriorAuthTransitions.isRoleAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.CANCELLED, PROVIDER));
        assertTrue(PriorAuthTransitions.isRoleAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.CANCELLED, COORDINATOR));
        // A reviewer does not cancel a request (that is the requester's action).
        assertFalse(PriorAuthTransitions.isRoleAllowed(
                PriorAuthorizationStatus.REQUESTED, PriorAuthorizationStatus.CANCELLED, REVIEWER));
    }

    @Test
    void a_decision_stamps_the_reviewer() {
        assertTrue(PriorAuthTransitions.isDecision(PriorAuthorizationStatus.APPROVED));
        assertTrue(PriorAuthTransitions.isDecision(PriorAuthorizationStatus.DENIED));
        assertFalse(PriorAuthTransitions.isDecision(PriorAuthorizationStatus.CANCELLED));
    }

    @Test
    void a_reason_is_required_only_to_deny_or_cancel() {
        assertTrue(PriorAuthTransitions.reasonRequired(PriorAuthorizationStatus.DENIED));
        assertTrue(PriorAuthTransitions.reasonRequired(PriorAuthorizationStatus.CANCELLED));
        assertFalse(PriorAuthTransitions.reasonRequired(PriorAuthorizationStatus.APPROVED));
        assertFalse(PriorAuthTransitions.reasonRequired(PriorAuthorizationStatus.REQUESTED));
    }
}
