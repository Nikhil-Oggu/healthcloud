package com.healthcloud.error;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Renders unauthenticated access to a protected endpoint as a 401 {@link ApiError} (no login-page
 * redirect — this is an API/BFF), keeping the error contract consistent with the rest of the API.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ApiErrorResponses.write(response, objectMapper,
                ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.defaultMessage());
    }
}
