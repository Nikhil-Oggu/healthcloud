package com.healthcloud.auth;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the authenticated user's context ("who am I"). The identity is read from the
 * backend-derived {@link UserContext} (populated per request from the session), not re-derived from
 * anything the client sent — the single trusted source for who the caller is and which tenant.
 */
@RestController
@RequestMapping("/api/v1")
public class CurrentUserController {

    private final UserContextAccessor userContext;

    public CurrentUserController(UserContextAccessor userContext) {
        this.userContext = userContext;
    }

    @GetMapping("/me")
    public CurrentUserDto me() {
        // Reaching here means SecurityConfig already required authentication; requireUser() defends
        // against the (unexpected) case of an authenticated principal that could not be resolved.
        UserContext ctx = userContext.requireUser();
        return new CurrentUserDto(
                ctx.userId(),
                ctx.email(),
                ctx.fullName(),
                ctx.organizationId(),
                ctx.organizationName(),
                List.copyOf(ctx.roles()));
    }
}
