package com.healthcloud.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * A stable, framework-agnostic page envelope for paged list reads (Phase 9). We return this rather than Spring
 * Data's {@code PageImpl} directly so the JSON shape is a contract we own (Spring's serialized page shape is
 * explicitly unstable across versions). Every future paged work-queue endpoint returns a {@code PageResponse}.
 *
 * @param content       the DTOs on this page (never null; empty on an out-of-range page)
 * @param page          the zero-based page index actually returned
 * @param size          the page size actually applied (post-clamp)
 * @param totalElements the total number of matching rows across all pages
 * @param totalPages    the total number of pages at this size
 * @param first         whether this is the first page
 * @param last          whether this is the last page
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    /** Map a Spring Data {@link Page} of entities into a {@code PageResponse} of DTOs. */
    public static <E, D> PageResponse<D> of(Page<E> page, Function<? super E, ? extends D> mapper) {
        List<D> content = page.getContent().stream().<D>map(mapper).toList();
        return new PageResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /**
     * An empty page for the requested {@link Pageable} — used to short-circuit a query that provably has no
     * results (e.g. a gated caller whose accessible-id set is empty), avoiding a needless DB round trip.
     */
    public static <D> PageResponse<D> empty(Pageable pageable) {
        return new PageResponse<>(
                List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0L, 0, true, true);
    }
}
