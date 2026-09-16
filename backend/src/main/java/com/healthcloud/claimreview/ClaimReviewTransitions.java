package com.healthcloud.claimreview;

import static com.healthcloud.claimreview.ClaimReviewStatus.CANCELLED;
import static com.healthcloud.claimreview.ClaimReviewStatus.OPEN;
import static com.healthcloud.claimreview.ClaimReviewStatus.RESOLVED;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The manual-review state machine (source-of-truth §Phase 6), a pure policy class with no I/O — the backend is
 * the sole enforcer (§12.1). Encodes which moves are structurally legal, which roles may perform them, and when a
 * reason is mandatory. The seventh pure-policy exemplar after {@code RequestTransitions}, {@code ClaimTransitions},
 * {@code ConsentPolicy}, {@code PriorAuthTransitions}, {@code ReferralTransitions} and {@code AppealTransitions}.
 *
 * <p>Role mapping (a defensible synthetic MVP choice, mirroring the appeal split of opener vs decider): an opener
 * (coordinator/reviewer/admin) opens and may cancel a review; the CLAIMS_REVIEWER (with admin) resolves it — the
 * reviewer's disposition. A reason is mandatory on every transition: RESOLVED needs the reviewer's conclusion, a
 * CANCELLED needs a rationale.
 */
final class ClaimReviewTransitions {

    private static final String CARE_COORDINATOR = "CARE_COORDINATOR";
    private static final String CLAIMS_REVIEWER = "CLAIMS_REVIEWER";
    private static final String ORG_ADMIN = "ORG_ADMIN";

    /** Structurally-legal moves, ignoring role. Terminal states (RESOLVED, CANCELLED) have none. */
    private static final Map<ClaimReviewStatus, Set<ClaimReviewStatus>> ALLOWED =
            new EnumMap<>(ClaimReviewStatus.class);

    static {
        ALLOWED.put(OPEN, Set.of(RESOLVED, CANCELLED));
        // RESOLVED and CANCELLED are terminal.
    }

    private ClaimReviewTransitions() {
    }

    /** Whether {@code from → to} is a legal move at all (ignoring who is asking). */
    static boolean isAllowed(ClaimReviewStatus from, ClaimReviewStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Whether a caller holding {@code roles} may perform the (already structurally-legal) move. */
    static boolean isRoleAllowed(ClaimReviewStatus from, ClaimReviewStatus to, Set<String> roles) {
        return switch (to) {
            case RESOLVED -> hasAny(roles, CLAIMS_REVIEWER, ORG_ADMIN);
            case CANCELLED -> hasAny(roles, CARE_COORDINATOR, CLAIMS_REVIEWER, ORG_ADMIN);
            default -> false;
        };
    }

    /** Whether a decision (RESOLVED) stamps the resolver + timestamp on the review. */
    static boolean isDecision(ClaimReviewStatus to) {
        return to == RESOLVED;
    }

    /** A reason is mandatory on every transition (the resolution conclusion, or a cancellation rationale). */
    static boolean reasonRequired(ClaimReviewStatus to) {
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
