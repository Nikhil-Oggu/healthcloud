package com.healthcloud.context;

/**
 * Request-thread storage for the resolved {@link UserContext}. Package-private: it is written only by
 * {@link UserContextFilter} (once per request, cleared in a finally block) and read only through
 * {@link UserContextAccessor}, so no other code can inject or forge a caller identity.
 */
final class UserContextHolder {

    private static final ThreadLocal<UserContext> CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    static void set(UserContext context) {
        CONTEXT.set(context);
    }

    static UserContext get() {
        return CONTEXT.get();
    }

    static void clear() {
        CONTEXT.remove();
    }
}
