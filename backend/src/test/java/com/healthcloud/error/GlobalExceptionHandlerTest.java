package com.healthcloud.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit test for the database-backstop conflict mapping: a unique-constraint violation and an
 * optimistic-lock failure both become a 409 {@code CONFLICT} with a generic message that never leaks
 * the underlying SQL / constraint name. Pure unit test — the handler has no dependencies.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void data_integrity_violation_maps_to_409_without_leaking_sql() {
        var ex = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"ux_patient_org_mrn\"");

        ResponseEntity<ApiError> response = handler.handleConflict(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertEquals("CONFLICT", body.code());
        assertFalse(body.message().contains("ux_patient_org_mrn"),
                "the 409 message must not leak the constraint name / SQL");
    }

    @Test
    void optimistic_lock_failure_maps_to_409() {
        var ex = new ObjectOptimisticLockingFailureException("row was updated by another transaction", null);

        ResponseEntity<ApiError> response = handler.handleConflict(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("CONFLICT", response.getBody().code());
    }
}
