package com.healthcloud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure {@link Csv} formatter (§Phase 9 — CSV export). No Spring/DB. */
class CsvTest {

    @Test
    void a_plain_value_is_emitted_as_is() {
        assertEquals("CLM-0001", Csv.field("CLM-0001"));
    }

    @Test
    void null_and_empty_become_an_empty_cell() {
        assertEquals("", Csv.field(null));
        assertEquals("", Csv.field(""));
    }

    @Test
    void a_value_with_a_comma_is_quoted() {
        assertEquals("\"Doe, Jane\"", Csv.field("Doe, Jane"));
    }

    @Test
    void embedded_quotes_are_doubled_and_the_value_is_quoted() {
        assertEquals("\"She said \"\"hi\"\"\"", Csv.field("She said \"hi\""));
    }

    @Test
    void a_value_with_a_line_break_is_quoted() {
        assertEquals("\"line1\nline2\"", Csv.field("line1\nline2"));
    }

    @Test
    void a_leading_formula_trigger_is_neutralized() {
        // A cell starting with =, +, -, @ would be evaluated as a formula in Excel/Sheets — prefix a quote.
        assertEquals("'=SUM(A1:A9)", Csv.field("=SUM(A1:A9)"));
        assertEquals("'+1", Csv.field("+1"));
        assertEquals("'-2", Csv.field("-2"));
        assertEquals("'@cmd", Csv.field("@cmd"));
    }

    @Test
    void a_formula_trigger_that_also_needs_quoting_gets_both() {
        // Neutralize first (prefix '), then RFC 4180-quote because of the comma.
        assertEquals("\"'=a,b\"", Csv.field("=a,b"));
    }

    @Test
    void a_row_joins_cells_with_commas_and_a_crlf_terminator() {
        assertEquals("a,b,c\r\n", Csv.row(List.of("a", "b", "c")));
    }

    @Test
    void a_row_fields_each_cell_and_renders_a_null_cell_as_empty() {
        assertEquals("id,\"Doe, Jane\",\r\n", Csv.row(Arrays.asList("id", "Doe, Jane", null)));
    }
}
