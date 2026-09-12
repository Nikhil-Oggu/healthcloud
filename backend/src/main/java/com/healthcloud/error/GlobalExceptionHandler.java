package com.healthcloud.error;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every exception that reaches the DispatcherServlet into the single {@link ApiError} shape.
 * Authentication/authorization failures raised earlier, inside the Spring Security filter chain, are
 * rendered separately by {@link RestAuthenticationEntryPoint}/{@link RestAccessDeniedHandler} so the
 * whole API — controllers and filters alike — speaks the same error contract.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Expected, client-facing errors thrown by our own code. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return build(ex.errorCode(), ex.getMessage(), ex.details());
    }

    /** Authorization denied by a method-level check after the request reached a controller. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage(), null);
    }

    /** Bean-validation failures on {@code @Valid} request bodies → per-field details. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
    }

    /** A required request parameter was missing (e.g. a form field). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        List<Map<String, String>> fields =
                List.of(Map.of("field", ex.getParameterName(), "message", "is required"));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
    }

    /** Anything unexpected: log the real cause server-side, return a generic 500 (never leak internals). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, Object details) {
        ApiError body = ApiError.of(code, message, CorrelationId.current(), details);
        return ResponseEntity.status(code.status()).body(body);
    }
}
