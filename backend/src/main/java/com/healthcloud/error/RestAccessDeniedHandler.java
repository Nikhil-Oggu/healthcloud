package com.healthcloud.error;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Renders an authorization failure inside the security filter chain (e.g. a missing/invalid CSRF
 * token on a state-changing request) as a 403 {@link ApiError}, matching the rest of the API.
 */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ApiErrorResponses.write(response, objectMapper,
                ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
    }
}
