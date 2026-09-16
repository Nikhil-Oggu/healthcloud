package com.healthcloud.appeal;

import static com.healthcloud.appeal.AppealStatus.OVERTURNED;
import static com.healthcloud.appeal.AppealStatus.SUBMITTED;
import static com.healthcloud.appeal.AppealStatus.UPHELD;
import static com.healthcloud.appeal.AppealStatus.WITHDRAWN;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The appeal state machine (source-of-truth §Phase 6), a pure policy class with no I/O — the backend is the sole
 * enforcer (§12.1). Encodes which moves are structurally legal, which roles may perform them, and when a reason
 * is mandatory. The sixth pure-policy exemplar after {@code RequestTransitions}, {@code ClaimTransitions},
 * {@code ConsentPolicy}, {@code PriorAuthTransitions} and {@code ReferralTransitions}.
 *
 * <p>Role mapping (a defensible synthetic MVP choice): the submitter side (provider/coordinator/admin) submits
 * and withdraws; the CLAIMS_REVIEWER (with admin) upholds or overturns a submitted appeal — appeals are a
 * claims-review function, like the prior-auth decision. Unlike the referral/prior-auth machines, a reason is
 * mandatory on EVERY transition — an appeal outcome (or withdrawal) always needs a rationale.
 */
final class AppealTransitions {

    private static final String PROVIDER = "PROVIDER";
    private static final String CARE_COORDINATOR = "CARE_COORDINATOR";
    private static final String ORG_ADMIN = "ORG_ADMIN";
    private static final String CLAIMS_REVIEWER = "CLAIMS_REVIEWER";

    /** Structurally-legal moves, ignoring role. Terminal states (UPHELD, OVERTURNED, WITHDRAWN) have none. */
    private static final Map<AppealStatus, Set<AppealStatus>> ALLOWED = new EnumMap<>(AppealStatus.class);

    static {
        ALLOWED.put(SUBMITTED, Set.of(UPHELD, OVERTURNED, WITHDRAWN));
        // UPHELD, OVERTURNED and WITHDRAWN are terminal.
    }

    private AppealTransitions() {
    }

    /** Whether {@code from → to} is a legal move at all (ignoring who is asking). */
    static boolean isAllowed(AppealStatus from, AppealStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Whether a caller holding {@code roles} may perform the (already structurally-legal) move. */
    static boolean isRoleAllowed(AppealStatus from, AppealStatus to, Set<String> roles) {
        return switch (to) {
            case UPHELD, OVERTURNED -> hasAny(roles, CLAIMS_REVIEWER, ORG_ADMIN);
            case WITHDRAWN -> hasAny(roles, PROVIDER, CARE_COORDINATOR, ORG_ADMIN);
            default -> false;
        };
    }

    /** Whether a decision (UPHELD/OVERTURNED) stamps the decider + timestamp on the appeal. */
    static boolean isDecision(AppealStatus to) {
        return to == UPHELD || to == OVERTURNED;
    }

    /** A reason is mandatory on every appeal transition (a decision or a withdrawal always needs a rationale). */
    static boolean reasonRequired(AppealStatus to) {
        return true;
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
