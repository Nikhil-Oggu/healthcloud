package com.healthcloud.error;

/**
 * The current operation needs a resolved tenant (organization) context, but the authenticated caller
 * has none — e.g. a user with no ACTIVE organization membership. Surfaced as 403 ACCESS_DENIED: the
 * user is known, but cannot act within any tenant.
 */
public class TenantContextRequiredException extends ApiException {

    public TenantContextRequiredException() {
        super(ErrorCode.ACCESS_DENIED, "No active organization context for the current user.");
    }
}
