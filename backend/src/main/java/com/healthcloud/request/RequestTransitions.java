package com.healthcloud.request;

import static com.healthcloud.request.ServiceRequestStatus.APPROVED;
import static com.healthcloud.request.ServiceRequestStatus.ASSIGNED;
import static com.healthcloud.request.ServiceRequestStatus.CANCELLED;
import static com.healthcloud.request.ServiceRequestStatus.CLOSED;
import static com.healthcloud.request.ServiceRequestStatus.DRAFT;
import static com.healthcloud.request.ServiceRequestStatus.NEEDS_INFORMATION;
import static com.healthcloud.request.ServiceRequestStatus.REJECTED;
import static com.healthcloud.request.ServiceRequestStatus.SUBMITTED;
import static com.healthcloud.request.ServiceRequestStatus.TRIAGED;
import static com.healthcloud.request.ServiceRequestStatus.UNDER_REVIEW;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The service-request state machine (source-of-truth §14.6). Encodes which moves are structurally
 * legal, which roles may perform them, and when a reason is mandatory. Pure logic, no I/O — the
 * backend is the sole enforcer of these rules (§12.1).
 *
 * <p>Cancellation authority is fixed by §14.6. The forward-transition role mapping is a defensible
 * synthetic choice for the MVP. The move to {@code ASSIGNED} is structurally legal here (TRIAGED →
 * ASSIGNED), but it is driven exclusively by {@code RequestAssignmentService} (assigning a user), never
 * by a bare status change — so a request can never be ASSIGNED with no assignee.
 */
final class RequestTransitions {

    private static final String PATIENT = "PATIENT";
    private static final String PROVIDER = "PROVIDER";
    private static final String CARE_COORDINATOR = "CARE_COORDINATOR";
    private static final String ORG_ADMIN = "ORG_ADMIN";

    /** Structurally-legal moves, ignoring role. Terminal states (CANCELLED, CLOSED) have no outgoing. */
    private static final Map<ServiceRequestStatus, Set<ServiceRequestStatus>> ALLOWED =
            new EnumMap<>(ServiceRequestStatus.class);

    static {
        ALLOWED.put(DRAFT, Set.of(SUBMITTED, CANCELLED));
        ALLOWED.put(SUBMITTED, Set.of(TRIAGED, CANCELLED));
        ALLOWED.put(TRIAGED, Set.of(ASSIGNED, CANCELLED));
        ALLOWED.put(ASSIGNED, Set.of(UNDER_REVIEW, CANCELLED));
        ALLOWED.put(UNDER_REVIEW, Set.of(NEEDS_INFORMATION, APPROVED, REJECTED));
        ALLOWED.put(NEEDS_INFORMATION, Set.of(UNDER_REVIEW, CANCELLED));
        ALLOWED.put(APPROVED, Set.of(CLOSED));
        ALLOWED.put(REJECTED, Set.of(CLOSED));
        // CANCELLED and CLOSED are terminal.
    }

    private RequestTransitions() {
    }

    /** Whether {@code from → to} is a legal move at all (ignoring who is asking). */
    static boolean isAllowed(ServiceRequestStatus from, ServiceRequestStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Whether a caller holding {@code roles} may perform the (already structurally-legal) move. */
    static boolean isRoleAllowed(ServiceRequestStatus from, ServiceRequestStatus to, Set<String> roles) {
        if (to == CANCELLED) {
            // §14.6: patient may cancel DRAFT/SUBMITTED/NEEDS_INFORMATION; coordinator/admin may cancel
            // SUBMITTED/TRIAGED/ASSIGNED/NEEDS_INFORMATION; provider has no cancellation authority.
            boolean patientCancel = roles.contains(PATIENT)
                    && (from == DRAFT || from == SUBMITTED || from == NEEDS_INFORMATION);
            boolean staffCancel = hasAny(roles, CARE_COORDINATOR, ORG_ADMIN)
                    && (from == SUBMITTED || from == TRIAGED || from == ASSIGNED || from == NEEDS_INFORMATION);
            return patientCancel || staffCancel;
        }
        return switch (to) {
            case SUBMITTED -> hasAny(roles, PATIENT, PROVIDER, CARE_COORDINATOR, ORG_ADMIN);
            case TRIAGED, ASSIGNED -> hasAny(roles, CARE_COORDINATOR, ORG_ADMIN);
            case UNDER_REVIEW, NEEDS_INFORMATION -> hasAny(roles, PROVIDER, CARE_COORDINATOR, ORG_ADMIN);
            case APPROVED, REJECTED, CLOSED -> hasAny(roles, CARE_COORDINATOR, ORG_ADMIN);
            default -> false;
        };
    }

    /** A reason is mandatory when cancelling or rejecting (§14.6: coordinator cancels "with a reason"). */
    static boolean reasonRequired(ServiceRequestStatus to) {
        return to == CANCELLED || to == REJECTED;
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
