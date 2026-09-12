package com.healthcloud.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces the deferred CSRF token to be resolved on each request so the readable XSRF-TOKEN cookie
 * is always written to the response. The SPA (built later) reads that cookie and echoes it back in
 * the X-XSRF-TOKEN header on state-changing requests. Standard Spring Security BFF pattern.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) {
            csrfToken.getToken(); // triggers the token to be persisted to the cookie
        }
        filterChain.doFilter(request, response);
    }
}
