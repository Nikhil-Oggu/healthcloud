package com.healthcloud.error;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

/**
 * Writes an {@link ApiError} directly to the servlet response. Used by the Spring Security handlers,
 * which run inside the filter chain (before the DispatcherServlet) and so cannot rely on
 * {@link GlobalExceptionHandler} / normal message conversion.
 */
final class ApiErrorResponses {

    private ApiErrorResponses() {
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper,
                      ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError body = ApiError.of(code, message, CorrelationId.current(), null);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
