package com.healthcloud.common;

import java.util.List;

/**
 * Turns already-authorized, already-masked values into CSV text (Phase 9 — the reporting/export piece). Pure and
 * DB-free (a helper, in the spirit of {@link SearchTerms} and {@link PageRequests}), so it is trivially unit-testable.
 *
 * <p>This class only <b>formats</b> — it never reads data. The security property "the export respects field masking"
 * is upheld by the caller feeding it the same field-safe DTOs the JSON read returns (§23.3: the backend is the only
 * trusted masker; a masked field is already {@code null} by the time it reaches here, so it exports as an empty cell).
 *
 * <p>Two formatting concerns, both about producing a correct, safe file:
 *
 * <ul>
 *   <li><b>RFC 4180 quoting.</b> A field containing a comma, a double-quote, or a line break is wrapped in double
 *       quotes and any embedded double-quote is doubled, so it survives as a single cell. Rows are terminated with
 *       {@code \r\n} (the RFC 4180 line terminator).</li>
 *   <li><b>CSV-injection ("formula injection") neutralization.</b> A cell whose value begins with {@code = + - @}
 *       (or a tab/CR) can be executed as a formula when the file is opened in Excel or Google Sheets. We defuse it
 *       by prefixing a single quote, so the cell is shown literally rather than evaluated. This matters because one
 *       exported column (a patient's name) is free text.</li>
 * </ul>
 */
public final class Csv {

    private Csv() {}

    /**
     * Format one value as a single CSV cell: defuse a leading formula trigger, then RFC 4180-quote if needed.
     *
     * @param raw the cell value (may be null → an empty cell)
     * @return the cell text, ready to place between commas
     */
    public static String field(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String value = raw;
        // Formula injection: a leading =, +, -, @, tab or CR makes a spreadsheet treat the cell as a formula.
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            value = "'" + value;
        }
        // RFC 4180: quote (and double embedded quotes) when the value contains a comma, quote, or line break.
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Format one CSV record from its cells: each cell is {@link #field(String) fielded}, joined with commas, and
     * terminated with {@code \r\n}.
     *
     * @param cells the ordered cell values for this row (a null cell becomes an empty cell)
     * @return the row text including its trailing line terminator
     */
    public static String row(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(field(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }
}
