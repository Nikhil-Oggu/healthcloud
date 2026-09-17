package com.healthcloud.common;

import com.healthcloud.error.ApiException;
import com.healthcloud.error.ErrorCode;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Turns caller-supplied paging/sorting parameters into a safe {@link Pageable} (Phase 9). Pure and DB-free (a
 * helper, in the spirit of our pure policy classes) so it is trivially unit-testable. Two guardrails matter:
 *
 * <ul>
 *   <li><b>Clamping</b> — {@code size} is clamped to {@code [1, MAX_SIZE]} and {@code page} to {@code >= 0}, so a
 *       caller cannot request an unbounded or nonsensical page.</li>
 *   <li><b>Sort allowlisting</b> — the sort field must be one the endpoint explicitly permits. An unknown field
 *       is a clean {@code 400} rather than a {@code PropertyReferenceException} 500, and — more importantly — a
 *       caller cannot order by (and thereby probe) an arbitrary column.</li>
 * </ul>
 *
 * <p>The {@code sort} string is {@code "field"} or {@code "field,dir"} where {@code dir} is {@code asc}/{@code
 * desc} (case-insensitive, defaulting to {@code asc}). A blank/null sort falls back to the endpoint's default.
 */
public final class PageRequests {

    /** The largest page a caller may request. A larger {@code size} is clamped down to this. */
    public static final int MAX_SIZE = 100;

    private PageRequests() {}

    /**
     * Build a safe {@link Pageable} from raw request params.
     *
     * @param page              the requested zero-based page (negative is clamped to 0)
     * @param size              the requested page size (clamped to {@code [1, MAX_SIZE]})
     * @param sort              the requested sort ({@code "field"} / {@code "field,dir"}); null/blank → default
     * @param allowedSortFields the field names this endpoint permits sorting by
     * @param defaultSort       the sort to apply when none is requested
     * @throws ApiException {@code VALIDATION_FAILED} (400) if the sort field is not allowed or the direction is
     *                      not {@code asc}/{@code desc}
     */
    public static Pageable toPageable(
            int page, int size, String sort, Set<String> allowedSortFields, Sort defaultSort) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        if (sort == null || sort.isBlank()) {
            return PageRequest.of(safePage, safeSize, defaultSort);
        }

        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        if (!allowedSortFields.contains(field)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Cannot sort by '" + field + "'. Allowed: " + allowedSortFields + ".");
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2 && !parts[1].isBlank()) {
            String dir = parts[1].trim();
            if (dir.equalsIgnoreCase("desc")) {
                direction = Sort.Direction.DESC;
            } else if (!dir.equalsIgnoreCase("asc")) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Sort direction must be 'asc' or 'desc', not '" + dir + "'.");
            }
        }

        return PageRequest.of(safePage, safeSize, Sort.by(direction, field));
    }
}
