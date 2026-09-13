package com.healthcloud.error;

/**
 * A requested state change is not a legal move from the resource's current status (e.g. approving a
 * DRAFT). Distinct from a stale-version {@link ConflictException}: this means "that action isn't
 * allowed now", not "someone else changed it". Maps to HTTP 409. The message references only status
 * names (safe to expose).
 */
public class InvalidStateTransitionException extends ApiException {

    public InvalidStateTransitionException(String message) {
        super(ErrorCode.INVALID_STATE_TRANSITION, message);
    }
}
