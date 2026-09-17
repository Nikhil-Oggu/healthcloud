package com.healthcloud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure, DB-free {@link SearchTerms} helper (Phase 9): a blank box is "no filter", and the
 * SQL {@code LIKE} wildcards a user types are escaped so they match literally.
 */
class SearchTermsTest {

    @Test
    void a_null_or_blank_box_means_no_filter() {
        assertNull(SearchTerms.likeContains(null));
        assertNull(SearchTerms.likeContains(""));
        assertNull(SearchTerms.likeContains("   "));
        assertNull(SearchTerms.likeContains("\t\n"));
    }

    @Test
    void a_plain_term_becomes_a_contains_pattern_and_is_trimmed() {
        assertEquals("%CLM-1%", SearchTerms.likeContains("CLM-1"));
        assertEquals("%CLM-1%", SearchTerms.likeContains("  CLM-1  "), "surrounding whitespace is trimmed");
    }

    @Test
    void like_wildcards_are_escaped_so_they_match_literally() {
        // % and _ are LIKE metacharacters; a user typing them means the literal characters.
        assertEquals("%50\\%%", SearchTerms.likeContains("50%"));
        assertEquals("%a\\_b%", SearchTerms.likeContains("a_b"));
    }

    @Test
    void the_escape_character_itself_is_escaped_first() {
        // A literal backslash must be doubled, and must not turn a following % into an escaped one by accident.
        assertEquals("%a\\\\b%", SearchTerms.likeContains("a\\b"));
        assertEquals("%\\\\\\%%", SearchTerms.likeContains("\\%"), "backslash then percent → both escaped");
    }
}
