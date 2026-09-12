package com.healthcloud.context;

import java.util.Set;
import java.util.UUID;

/**
 * An immutable snapshot of the authenticated caller for the current request, derived entirely on the
 * backend from the session — never from client-supplied headers or parameters. This is the trusted
 * answer to "who is calling, which tenant are they acting in, and what roles do they hold". The
 * {@code organizationId} is the tenant key that all tenant-scoped queries must be constrained by.
 */
public record UserContext(
        UUID userId,
        String email,
        String fullName,
        UUID organizationId,
        String organizationName,
        Set<String> roles) {
}
