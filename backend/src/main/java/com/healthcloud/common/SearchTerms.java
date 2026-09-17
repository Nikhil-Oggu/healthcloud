package com.healthcloud.common;

/**
 * Turns a caller-supplied free-text search box into a safe SQL {@code LIKE} pattern (Phase 9). Pure and DB-free
 * (a helper, in the spirit of {@link PageRequests} and our pure policy classes) so it is trivially unit-testable.
 *
 * <p>Two things matter here — neither is about injection (the term is always a bound query parameter, never
 * string-concatenated into SQL), both are about correctness:
 *
 * <ul>
 *   <li><b>Blank means "no filter."</b> A null, empty, or whitespace-only box returns {@code null}, and every
 *       search query is written {@code (:q is null or ... like :q ...)} so a null term disables the clause. An
 *       empty search box therefore lists everything, rather than matching the literal empty string.</li>
 *   <li><b>The {@code LIKE} wildcards are escaped.</b> A user typing a literal {@code %} or {@code _} means those
 *       characters, not "match any run" / "match any char" — so we escape {@code \ % _} and pair every query with
 *       {@code escape '\'}. Without this a user searching for "50%" would match half the table.</li>
 * </ul>
 *
 * <p>The result already carries the surrounding {@code %} wildcards, so a query does a "contains" match:
 * {@code lower(col) like lower(:q) escape '\'} (both sides lowered for a case-insensitive search).
 */
public final class SearchTerms {

    /** The character the {@code LIKE ... escape} clauses in our queries use. Keep the two in sync. */
    public static final char ESCAPE = '\\';

    private SearchTerms() {}

    /**
     * Build a case-insensitive "contains" {@code LIKE} pattern from a raw search box, or {@code null} for a
     * blank box (meaning "no filter").
     *
     * @param raw the raw text the caller typed (may be null/blank)
     * @return {@code %<escaped term>%}, or {@code null} if the box was null/empty/whitespace
     */
    public static String likeContains(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String escaped = trimmed
                .replace("\\", "\\\\") // escape the escape char first, so we don't double-escape below
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
