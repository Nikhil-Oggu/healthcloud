package com.healthcloud.error;

/**
 * A resource does not exist — or, for existence-sensitive resources, the caller is not allowed to
 * know whether it exists (source-of-truth §31: "secure 404 for existence-sensitive denials"). Keep
 * the message generic so it never confirms the existence of another tenant's data.
 */
public class NotFoundException extends ApiException {

    public NotFoundException() {
        super(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
