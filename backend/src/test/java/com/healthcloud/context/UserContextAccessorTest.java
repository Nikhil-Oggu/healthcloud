package com.healthcloud.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.error.ApiException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.TenantContextRequiredException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the trusted caller-context accessor (Phase 1 slice 5). Lives in the same package so
 * it can seed the request-thread holder directly, simulating what {@code UserContextFilter} does.
 * Proves the "require" guards: no context → 401, no active tenant → 403.
 */
class UserContextAccessorTest {

    private final UserContextAccessor accessor = new UserContextAccessor();

    @AfterEach
    void clearThreadLocal() {
        UserContextHolder.clear();
    }

    @Test
    void current_is_empty_when_no_context() {
        assertTrue(accessor.current().isEmpty());
    }

    @Test
    void requireUser_throws_unauthenticated_when_no_context() {
        ApiException ex = assertThrows(ApiException.class, accessor::requireUser);
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.errorCode());
    }

    @Test
    void requireOrganizationId_returns_tenant_when_present() {
        UUID org = UUID.randomUUID();
        UserContextHolder.set(new UserContext(
                UUID.randomUUID(), "u@northcare.example.org", "U", org, "NorthCare Health", Set.of("PROVIDER")));

        assertEquals(org, accessor.requireOrganizationId());
    }

    @Test
    void requireOrganizationId_throws_access_denied_when_user_has_no_active_tenant() {
        UserContextHolder.set(new UserContext(
                UUID.randomUUID(), "orphan@example.org", "Orphan", null, null, Set.of()));

        assertThrows(TenantContextRequiredException.class, accessor::requireOrganizationId);
    }
}
