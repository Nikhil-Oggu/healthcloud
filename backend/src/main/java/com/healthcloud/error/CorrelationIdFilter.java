package com.healthcloud.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id: it reuses a safe inbound {@code X-Correlation-Id} when one is
 * provided, otherwise generates a UUID. The id is exposed to logs via the {@link MDC} and echoed on
 * the response header so a client (or a support engineer reading logs) can tie a specific response
 * back to its server-side trace.
 *
 * <p>Registered at highest precedence so it runs <em>before</em> Spring Security — that way even
 * 401/403 responses produced inside the security filter chain carry a correlation id.
 *
 * <p>An inbound id is only trusted if it is short and alphanumeric-with-dashes; anything else is
 * replaced, which prevents log-forging and response-header injection via a hostile header value.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationId.HEADER);
        if (!isSafe(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private static boolean isSafe(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
