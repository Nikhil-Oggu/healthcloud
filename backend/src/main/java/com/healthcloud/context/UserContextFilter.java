package com.healthcloud.context;

import com.healthcloud.auth.CurrentUserDto;
import com.healthcloud.auth.CurrentUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the request-scoped {@link UserContext} from the authenticated session (if any) by
 * resolving the principal to its user, active organization and roles on the backend — the identity
 * the rest of the request can trust. Registered in the security filter chain (after authorization)
 * and always cleared at the end of the request so nothing leaks between requests or threads.
 *
 * <p>Not a Spring bean: it is constructed and wired into the chain by {@code SecurityConfig}, which
 * keeps it registered exactly once and guarantees its position relative to Spring Security.
 */
public class UserContextFilter extends OncePerRequestFilter {

    private final CurrentUserService currentUserService;

    public UserContextFilter(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (isAuthenticated(authentication)) {
                currentUserService.resolveByEmail(authentication.getName())
                        .ifPresent(dto -> UserContextHolder.set(toContext(dto)));
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
    }

    private static UserContext toContext(CurrentUserDto dto) {
        return new UserContext(
                dto.userId(),
                dto.email(),
                dto.fullName(),
                dto.organizationId(),
                dto.organizationName(),
                new LinkedHashSet<>(dto.roles()));
    }
}
