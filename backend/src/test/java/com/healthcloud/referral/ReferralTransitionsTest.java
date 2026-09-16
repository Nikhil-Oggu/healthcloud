package com.healthcloud.referral;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure referral state-machine policy (no Spring/DB). Covers which moves are structurally
 * legal, which roles may perform them, which are decisions, and when a reason is mandatory.
 */
class ReferralTransitionsTest {

    private static final Set<String> PROVIDER = Set.of("PROVIDER");
    private static final Set<String> COORDINATOR = Set.of("CARE_COORDINATOR");
    private static final Set<String> REVIEWER = Set.of("CLAIMS_REVIEWER");
    private static final Set<String> ADMIN = Set.of("ORG_ADMIN");

    @Test
    void legal_moves_follow_the_lifecycle() {
        assertTrue(ReferralTransitions.isAllowed(ReferralStatus.REQUESTED, ReferralStatus.APPROVED));
        assertTrue(ReferralTransitions.isAllowed(ReferralStatus.REQUESTED, ReferralStatus.DENIED));
        assertTrue(ReferralTransitions.isAllowed(ReferralStatus.REQUESTED, ReferralStatus.CANCELLED));

        // Terminal states have no outgoing moves.
        assertFalse(ReferralTransitions.isAllowed(ReferralStatus.APPROVED, ReferralStatus.DENIED));
        assertFalse(ReferralTransitions.isAllowed(ReferralStatus.DENIED, ReferralStatus.REQUESTED));
        assertFalse(ReferralTransitions.isAllowed(ReferralStatus.CANCELLED, ReferralStatus.APPROVED));
    }

    @Test
    void approve_and_deny_are_the_coordinator_roles() {
        assertTrue(ReferralTransitions.isRoleAllowed(
                ReferralStatus.REQUESTED, ReferralStatus.APPROVED, COORDINATOR));
        assertTrue(ReferralTransitions.isRoleAllowed(
                ReferralStatus.REQUESTED, ReferralStatus.DENIED, ADMIN));
        // A provider does not decide a referral; nor does a claims reviewer (referrals are care coordination).
        assertFalse(ReferralTransitions.isRoleAllowed(
                ReferralStatus.REQUESTED, ReferralStatus.APPROVED, PROVIDER));
        assertFalse(ReferralTransitions.isRoleAllowed(
                ReferralStatus.REQUESTED, ReferralStatus.DENIED, REVIEWER));
    }

    @Test
    void cancel_is_the_requester_roles() {
        assertTrue(ReferralTransitions.isRoleAllowed(
                ReferralStatus.REQUESTED, ReferralStatus.CANCELLED, PROVIDER));
        assertTrue(ReferralTransitions.isRoleAllowed(
                ReferralStatus.REQUESTED, ReferralStatus.CANCELLED, COORDINATOR));
        // A reviewer does not cancel a referral (that is the requester's action).
        assertFalse(ReferralTransitions.isRoleAllowed(
                ReferralStatus.REQUESTED, ReferralStatus.CANCELLED, REVIEWER));
    }

    @Test
    void a_decision_stamps_the_decider() {
        assertTrue(ReferralTransitions.isDecision(ReferralStatus.APPROVED));
        assertTrue(ReferralTransitions.isDecision(ReferralStatus.DENIED));
        assertFalse(ReferralTransitions.isDecision(ReferralStatus.CANCELLED));
    }

    @Test
    void a_reason_is_required_only_to_deny_or_cancel() {
        assertTrue(ReferralTransitions.reasonRequired(ReferralStatus.DENIED));
        assertTrue(ReferralTransitions.reasonRequired(ReferralStatus.CANCELLED));
        assertFalse(ReferralTransitions.reasonRequired(ReferralStatus.APPROVED));
        assertFalse(ReferralTransitions.reasonRequired(ReferralStatus.REQUESTED));
    }
}
