package com.healthcloud.context;

import com.healthcloud.error.ApiException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.TenantContextRequiredException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one, injectable way for services and controllers to read the backend-derived caller context.
 * This is the trusted source of the caller's identity and tenant for the current request; business
 * code must obtain the {@code organizationId} here and must never accept it from the client.
 */
@Component
public class UserContextAccessor {

    /** The caller context if the request is authenticated and resolved, otherwise empty. */
    public Optional<UserContext> current() {
        return Optional.ofNullable(UserContextHolder.get());
    }

    /** The caller context; throws 401 if there is no authenticated, resolved user on this request. */
    public UserContext requireUser() {
        UserContext context = UserContextHolder.get();
        if (context == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.defaultMessage());
        }
        return context;
    }

    /** The tenant the caller is acting in; throws 403 if the caller has no active organization. */
    public UUID requireOrganizationId() {
        UserContext context = requireUser();
        if (context.organizationId() == null) {
            throw new TenantContextRequiredException();
        }
        return context.organizationId();
    }
}
