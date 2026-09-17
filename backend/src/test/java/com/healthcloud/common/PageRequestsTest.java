package com.healthcloud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.healthcloud.error.ApiException;
import com.healthcloud.error.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pure unit tests for {@link PageRequests} — the paging/sorting guardrails (Phase 9): size/page clamping, the
 * default sort, {@code "field,dir"} parsing, and the sort-field allowlist (an unknown field or bad direction is a
 * clean {@code 400}, not a 500 or an arbitrary-column sort). No Spring or DB.
 */
class PageRequestsTest {

    private static final Set<String> ALLOWED = Set.of("createdAt", "serviceDate", "totalChargeAmount");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void a_blank_sort_uses_the_default_sort_and_the_requested_page_and_size() {
        Pageable pageable = PageRequests.toPageable(2, 25, null, ALLOWED, DEFAULT_SORT);
        assertEquals(2, pageable.getPageNumber());
        assertEquals(25, pageable.getPageSize());
        assertEquals(DEFAULT_SORT, pageable.getSort());
    }

    @Test
    void size_is_clamped_to_the_allowed_range() {
        assertEquals(1, PageRequests.toPageable(0, 0, null, ALLOWED, DEFAULT_SORT).getPageSize(),
                "a size below 1 is clamped up to 1");
        assertEquals(1, PageRequests.toPageable(0, -5, null, ALLOWED, DEFAULT_SORT).getPageSize());
        assertEquals(PageRequests.MAX_SIZE,
                PageRequests.toPageable(0, 10_000, null, ALLOWED, DEFAULT_SORT).getPageSize(),
                "a size above the max is clamped down to the max");
    }

    @Test
    void a_negative_page_is_clamped_to_zero() {
        assertEquals(0, PageRequests.toPageable(-3, 20, null, ALLOWED, DEFAULT_SORT).getPageNumber());
    }

    @Test
    void a_field_only_sort_defaults_to_ascending() {
        Sort sort = PageRequests.toPageable(0, 20, "serviceDate", ALLOWED, DEFAULT_SORT).getSort();
        Sort.Order order = sort.getOrderFor("serviceDate");
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void a_field_and_direction_sort_is_parsed_case_insensitively() {
        Sort sort = PageRequests.toPageable(0, 20, "totalChargeAmount,DESC", ALLOWED, DEFAULT_SORT).getSort();
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("totalChargeAmount").getDirection());
    }

    @Test
    void an_unknown_sort_field_is_a_400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> PageRequests.toPageable(0, 20, "ssn", ALLOWED, DEFAULT_SORT));
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.errorCode());
    }

    @Test
    void an_invalid_sort_direction_is_a_400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> PageRequests.toPageable(0, 20, "serviceDate,sideways", ALLOWED, DEFAULT_SORT));
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.errorCode());
    }
}
