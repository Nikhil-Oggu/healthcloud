package com.healthcloud.error;

/**
 * The request conflicts with the current state of the resource — e.g. a duplicate unique value, or a
 * stale optimistic-lock version (someone else changed the row since the caller last read it). Maps to
 * HTTP 409. The message must stay safe to expose (no internal/sensitive detail).
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
