package com.healthcloud.error;

/**
 * Base class for expected, client-facing errors. Carries an {@link ErrorCode} (which fixes the HTTP
 * status), a safe message intended for the client, and optional structured {@code details}. Anything
 * thrown as an {@code ApiException} is rendered by {@link GlobalExceptionHandler} into an
 * {@link ApiError}; its message must therefore be safe to expose (no sensitive or internal data).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object details;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ApiException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object details() {
        return details;
    }
}
