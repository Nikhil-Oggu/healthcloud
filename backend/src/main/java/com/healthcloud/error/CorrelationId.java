package com.healthcloud.error;

import org.slf4j.MDC;

/**
 * Helpers for the per-request correlation id. The id is stored in the SLF4J {@link MDC} (so it is
 * attached to every log line on the request thread) by {@link CorrelationIdFilter}, and read back
 * here when building an {@link ApiError} so a client-visible error can be tied to the server logs.
 */
public final class CorrelationId {

    /** Request/response header used to carry the correlation id. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key under which the correlation id is stored for logging. */
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /** The correlation id for the current request thread, or {@code null} if none is set. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
