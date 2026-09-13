package com.healthcloud.error;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    /** A path/query parameter couldn't be converted to its type (e.g. an unknown enum value) → 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        List<Map<String, String>> fields = List.of(Map.of("field", ex.getName(), "message", "is invalid"));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
    }

    /**
     * The database backstop for concurrent writes. Our services pre-check (expected version, unique
     * value) for friendly messages, but under a true race two writers can both pass the pre-check and
     * collide at the DB: a lost-update (optimistic {@code @Version}) or a unique-constraint violation.
     * Both are genuine conflicts → 409, with a generic message (never echo the SQL/constraint detail).
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ApiError> handleConflict(Exception ex) {
        log.warn("Data conflict mapped to 409: {}", ex.getClass().getSimpleName());
        return build(ErrorCode.CONFLICT, ErrorCode.CONFLICT.defaultMessage(), null);
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
