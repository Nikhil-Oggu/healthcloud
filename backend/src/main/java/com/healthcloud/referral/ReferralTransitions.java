package com.healthcloud.referral;

import static com.healthcloud.referral.ReferralStatus.APPROVED;
import static com.healthcloud.referral.ReferralStatus.CANCELLED;
import static com.healthcloud.referral.ReferralStatus.DENIED;
import static com.healthcloud.referral.ReferralStatus.REQUESTED;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The referral state machine (source-of-truth §Phase 6), a pure policy class with no I/O — the backend is the
 * sole enforcer (§12.1). Encodes which moves are structurally legal, which roles may perform them, and when a
 * reason is mandatory. The fifth pure-policy exemplar after {@code RequestTransitions}, {@code ClaimTransitions},
 * {@code ConsentPolicy} and {@code PriorAuthTransitions}.
 *
 * <p>Role mapping (a defensible synthetic MVP choice): the requester side (provider/coordinator/admin) requests
 * and cancels; a CARE_COORDINATOR (with admin) approves or denies a requested referral — referral routing is
 * care coordination's call. This deliberately differs from the prior-auth machine (where the CLAIMS_REVIEWER
 * decides), showing the layered pattern generalizes across roles.
 */
final class ReferralTransitions {

    private static final String PROVIDER = "PROVIDER";
    private static final String CARE_COORDINATOR = "CARE_COORDINATOR";
    private static final String ORG_ADMIN = "ORG_ADMIN";

    /** Structurally-legal moves, ignoring role. Terminal states (APPROVED, DENIED, CANCELLED) have none. */
    private static final Map<ReferralStatus, Set<ReferralStatus>> ALLOWED = new EnumMap<>(ReferralStatus.class);

    static {
        ALLOWED.put(REQUESTED, Set.of(APPROVED, DENIED, CANCELLED));
        // APPROVED, DENIED and CANCELLED are terminal.
    }

    private ReferralTransitions() {
    }

    /** Whether {@code from → to} is a legal move at all (ignoring who is asking). */
    static boolean isAllowed(ReferralStatus from, ReferralStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Whether a caller holding {@code roles} may perform the (already structurally-legal) move. */
    static boolean isRoleAllowed(ReferralStatus from, ReferralStatus to, Set<String> roles) {
        return switch (to) {
            case APPROVED, DENIED -> hasAny(roles, CARE_COORDINATOR, ORG_ADMIN);
            case CANCELLED -> hasAny(roles, PROVIDER, CARE_COORDINATOR, ORG_ADMIN);
            default -> false;
        };
    }

    /** Whether a decision (APPROVED/DENIED) stamps the decider + timestamp on the referral. */
    static boolean isDecision(ReferralStatus to) {
        return to == APPROVED || to == DENIED;
    }

    /** A reason is mandatory when denying or cancelling. */
    static boolean reasonRequired(ReferralStatus to) {
        return to == DENIED || to == CANCELLED;
    }

    private static boolean hasAny(Set<String> roles, String... allowed) {
        for (String role : allowed) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
