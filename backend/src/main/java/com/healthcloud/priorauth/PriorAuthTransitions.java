package com.healthcloud.priorauth;

import static com.healthcloud.priorauth.PriorAuthorizationStatus.APPROVED;
import static com.healthcloud.priorauth.PriorAuthorizationStatus.CANCELLED;
import static com.healthcloud.priorauth.PriorAuthorizationStatus.DENIED;
import static com.healthcloud.priorauth.PriorAuthorizationStatus.REQUESTED;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The prior-authorization state machine (source-of-truth §Phase 6), a pure policy class with no I/O — the
 * backend is the sole enforcer (§12.1). Encodes which moves are structurally legal, which roles may perform
 * them, and when a reason is mandatory. The fourth pure-policy exemplar after {@code RequestTransitions},
 * {@code ClaimTransitions} and {@code ConsentPolicy}.
 *
 * <p>Role mapping (a defensible synthetic MVP choice, mirroring the claim machine): the requester side
 * (provider/coordinator/admin) requests and cancels; the CLAIMS_REVIEWER (with admin) approves or denies a
 * requested authorization — the reviewer's decision action.
 */
final class PriorAuthTransitions {

    private static final String PROVIDER = "PROVIDER";
    private static final String CARE_COORDINATOR = "CARE_COORDINATOR";
    private static final String ORG_ADMIN = "ORG_ADMIN";
    private static final String CLAIMS_REVIEWER = "CLAIMS_REVIEWER";

    /** Structurally-legal moves, ignoring role. Terminal states (APPROVED, DENIED, CANCELLED) have none. */
    private static final Map<PriorAuthorizationStatus, Set<PriorAuthorizationStatus>> ALLOWED =
            new EnumMap<>(PriorAuthorizationStatus.class);

    static {
        ALLOWED.put(REQUESTED, Set.of(APPROVED, DENIED, CANCELLED));
        // APPROVED, DENIED and CANCELLED are terminal.
    }

    private PriorAuthTransitions() {
    }

    /** Whether {@code from → to} is a legal move at all (ignoring who is asking). */
    static boolean isAllowed(PriorAuthorizationStatus from, PriorAuthorizationStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Whether a caller holding {@code roles} may perform the (already structurally-legal) move. */
    static boolean isRoleAllowed(PriorAuthorizationStatus from, PriorAuthorizationStatus to, Set<String> roles) {
        return switch (to) {
            case APPROVED, DENIED -> hasAny(roles, CLAIMS_REVIEWER, ORG_ADMIN);
            case CANCELLED -> hasAny(roles, PROVIDER, CARE_COORDINATOR, ORG_ADMIN);
            default -> false;
        };
    }

    /** Whether a decision (APPROVED/DENIED) stamps the reviewer + timestamp on the authorization. */
    static boolean isDecision(PriorAuthorizationStatus to) {
        return to == APPROVED || to == DENIED;
    }

    /** A reason is mandatory when denying or cancelling. */
    static boolean reasonRequired(PriorAuthorizationStatus to) {
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
