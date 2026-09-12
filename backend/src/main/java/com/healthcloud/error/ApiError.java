package com.healthcloud.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error shape returned by the whole API (frozen contract, source-of-truth §31):
 * {@code {code, message, correlationId, details}}. {@code details} is optional (omitted from JSON
 * when null) and carries structured, non-sensitive extra info such as per-field validation errors.
 * Never put sensitive data or internal exception text in {@code message} or {@code details}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String correlationId, Object details) {

    public static ApiError of(ErrorCode code, String message, String correlationId, Object details) {
        return new ApiError(code.name(), message, correlationId, details);
    }
}
