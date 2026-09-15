package com.healthcloud.claim;

import static com.healthcloud.claim.ClaimStatus.ACCEPTED;
import static com.healthcloud.claim.ClaimStatus.ADJUDICATED;
import static com.healthcloud.claim.ClaimStatus.CANCELLED;
import static com.healthcloud.claim.ClaimStatus.DRAFT;
import static com.healthcloud.claim.ClaimStatus.REJECTED;
import static com.healthcloud.claim.ClaimStatus.SUBMITTED;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The claim state machine (source-of-truth §Phase 4 submission/validation), a pure policy class with no I/O —
 * the backend is the sole enforcer (§12.1). Encodes which moves are structurally legal, which roles may perform
 * them, and when a reason is mandatory. Mirrors {@code RequestTransitions}.
 *
 * <p>{@code ACCEPTED → ADJUDICATED} is structurally legal here, but adjudication is <b>owned by the Phase-5
 * adjudication engine</b>, not a bare status change (like {@code ASSIGNED} on a service request) — the service
 * refuses a plain {@code PATCH /status} to ADJUDICATED. The role mapping is a defensible synthetic MVP choice:
 * the submitter side (provider/coordinator/admin) submits and cancels; the CLAIMS_REVIEWER (with admin) accepts
 * or rejects a submitted claim.
 */
final class ClaimTransitions {

    private static final String PROVIDER = "PROVIDER";
    private static final String CARE_COORDINATOR = "CARE_COORDINATOR";
    private static final String ORG_ADMIN = "ORG_ADMIN";
    private static final String CLAIMS_REVIEWER = "CLAIMS_REVIEWER";

    /** Structurally-legal moves, ignoring role. Terminal states (REJECTED, ADJUDICATED, CANCELLED) have none. */
    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = new EnumMap<>(ClaimStatus.class);

    static {
        ALLOWED.put(DRAFT, Set.of(SUBMITTED, CANCELLED));
        ALLOWED.put(SUBMITTED, Set.of(ACCEPTED, REJECTED, CANCELLED));
        ALLOWED.put(ACCEPTED, Set.of(ADJUDICATED)); // ADJUDICATED is engine-owned (see class doc)
        // REJECTED, ADJUDICATED and CANCELLED are terminal.
    }

    private ClaimTransitions() {
    }

    /** Whether {@code from → to} is a legal move at all (ignoring who is asking). */
    static boolean isAllowed(ClaimStatus from, ClaimStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Whether a caller holding {@code roles} may perform the (already structurally-legal) move. */
    static boolean isRoleAllowed(ClaimStatus from, ClaimStatus to, Set<String> roles) {
        return switch (to) {
            case SUBMITTED, CANCELLED -> hasAny(roles, PROVIDER, CARE_COORDINATOR, ORG_ADMIN);
            case ACCEPTED, REJECTED -> hasAny(roles, CLAIMS_REVIEWER, ORG_ADMIN);
            // ADJUDICATED is reached only by the adjudication engine, never a bare status change.
            default -> false;
        };
    }

    /** A reason is mandatory when rejecting or cancelling. */
    static boolean reasonRequired(ClaimStatus to) {
        return to == REJECTED || to == CANCELLED;
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
