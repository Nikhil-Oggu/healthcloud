package com.healthcloud.error;

import org.springframework.http.HttpStatus;

/**
 * The stable, machine-readable error codes returned in every {@link ApiError}. The name (e.g.
 * {@code NOT_FOUND}) is the contract the frontend switches on; the HTTP status and default message
 * are the safe server-side defaults. Codes are additive — never renumber or repurpose an existing one.
 */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have access to this resource."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request was invalid."),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state of the resource."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
