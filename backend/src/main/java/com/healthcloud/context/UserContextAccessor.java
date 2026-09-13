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

    /**
     * Backend authorization gate: require that the caller holds at least one of the given role codes,
     * otherwise 403. The frontend may also hide UI, but this is the enforced boundary (roles come from
     * the backend-derived context, never from the client).
     */
    public void requireAnyRole(String... roleCodes) {
        UserContext context = requireUser();
        for (String roleCode : roleCodes) {
            if (context.roles().contains(roleCode)) {
                return;
            }
        }
        throw new ApiException(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
    }
}
