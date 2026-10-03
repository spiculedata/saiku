/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A CSV file parsed into a header + typed rows, ready for {@link CsvTableLoader} to load into a
 * relational table. Pure — no I/O, no JDBC; parses an already-decoded {@link String} so callers
 * (streaming from a multipart upload) control how the bytes are read and size-limited.
 *
 * <p>Column names are sanitised via {@link QuickstartNames#sanitizeHeader} — blank header cells
 * and duplicate names are resolved deterministically rather than failing the upload.
 *
 * <p>Type inference is per-column and order-independent: a column is {@link ColumnType#LONG}
 * only if every non-blank value in it parses as a (signed) integer, {@link ColumnType#DOUBLE}
 * only if every value parses as a decimal number, {@link ColumnType#DATE} only if every value is
 * an ISO-8601 {@code yyyy-MM-dd} date, and {@link ColumnType#STRING} otherwise — including for a
 * column that is entirely blank, where there is nothing to infer from. A column mixing types
 * (saiku#1117's "bad CSV" test-plan case) always widens to STRING rather than failing the
 * upload; only a genuinely malformed file (no header, a ragged row) raises
 * {@link CsvIngestException}.
 */
public final class CsvTable {

    /** SQL type family a column was inferred as. */
    public enum ColumnType {
        LONG,
        DOUBLE,
        DATE,
        STRING
    }

    /** One inferred column: its sanitised name and inferred type. */
    public record Column(String name, ColumnType type) {}

    private static final Pattern LONG_PATTERN = Pattern.compile("[-+]?\\d+");
    private static final Pattern DOUBLE_PATTERN =
            Pattern.compile("[-+]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][-+]?\\d+)?");

    private final List<Column> columns;
    private final List<List<String>> rows;

    private CsvTable(List<Column> columns, List<List<String>> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public List<Column> columns() {
        return columns;
    }

    /** Raw (unparsed) cell values, one list per row, in column order. Blank cell = {@code ""}. */
    public List<List<String>> rows() {
        return rows;
    }

    /**
     * Parse {@code csv} (the whole file, already decoded to text) into a header + typed rows.
     *
     * @throws CsvIngestException the file is empty, has no header, or a data row's column count
     *     doesn't match the header's
     */
    public static CsvTable parse(String csv) {
        List<List<String>> records = parseRecords(csv);
        if (records.isEmpty()) {
            throw new CsvIngestException("the CSV file is empty");
        }
        List<String> header = records.get(0);
        List<String> names = QuickstartNames.sanitizeHeader(header);
        List<List<String>> dataRows = records.subList(1, records.size());
        if (dataRows.isEmpty()) {
            throw new CsvIngestException("the CSV file has a header row but no data rows");
        }
        for (int i = 0; i < dataRows.size(); i++) {
            List<String> row = dataRows.get(i);
            if (row.size() != names.size()) {
                throw new CsvIngestException("row " + (i + 2) + " has " + row.size()
                        + " column(s) but the header has " + names.size()
                        + " — every row must have the same number of columns as the header");
            }
        }
        List<Column> columns = new ArrayList<>(names.size());
        for (int c = 0; c < names.size(); c++) {
            columns.add(new Column(names.get(c), inferColumnType(c, dataRows)));
        }
        return new CsvTable(columns, dataRows);
    }

    private static ColumnType inferColumnType(int columnIndex, List<List<String>> rows) {
        boolean sawValue = false;
        boolean allLong = true;
        boolean allDouble = true;
        boolean allDate = true;
        for (List<String> row : rows) {
            String v = row.get(columnIndex) == null ? "" : row.get(columnIndex).trim();
            if (v.isEmpty()) {
                continue;
            }
            sawValue = true;
            allLong = allLong && isLong(v);
            allDouble = allDouble && isDouble(v);
            allDate = allDate && isIsoDate(v);
            if (!allLong && !allDouble && !allDate) {
                // Fully widened to STRING already — no need to keep scanning this column.
                break;
            }
        }
        if (!sawValue) {
            return ColumnType.STRING;
        }
        if (allLong) {
            return ColumnType.LONG;
        }
        if (allDouble) {
            return ColumnType.DOUBLE;
        }
        if (allDate) {
            return ColumnType.DATE;
        }
        return ColumnType.STRING;
    }

    private static boolean isLong(String v) {
        if (!LONG_PATTERN.matcher(v).matches()) {
            return false;
        }
        try {
            Long.parseLong(v);
            return true;
        } catch (NumberFormatException tooBigForLong) {
            return false;
        }
    }

    private static boolean isDouble(String v) {
        if (!DOUBLE_PATTERN.matcher(v).matches()) {
            return false;
        }
        try {
            Double.parseDouble(v);
            return true;
        } catch (NumberFormatException notActuallyNumeric) {
            return false;
        }
    }

    private static boolean isIsoDate(String v) {
        try {
            LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException notADate) {
            return false;
        }
    }

    /**
     * RFC 4180-ish tokeniser: comma-separated fields, {@code "…"} quoting with {@code ""} as an
     * escaped quote, and CR / LF / CRLF all accepted as a row terminator. Operates on the whole
     * decoded string (no streaming) — the caller size-limits the upload, so this stays a simple,
     * easy-to-verify single pass over a char array rather than a stateful reader with lookahead.
     *
     * <p>A record that is exactly one blank field (a bare blank line) is dropped rather than
     * treated as a one-column row — otherwise a trailing newline at end-of-file, or a spacer
     * blank line partway through, would either fabricate a phantom last record or trip the
     * ragged-row check in {@link #parse}.
     */
    private static List<List<String>> parseRecords(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean recordHasContent = false;
        int len = csv.length();
        int i = 0;
        while (i < len) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                recordHasContent = true;
                i++;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                recordHasContent = true;
                i++;
            } else if (c == '\r' || c == '\n') {
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
                recordHasContent = false;
                i++;
                if (c == '\r' && i < len && csv.charAt(i) == '\n') {
                    i++;
                }
            } else {
                field.append(c);
                recordHasContent = true;
                i++;
            }
        }
        if (recordHasContent || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        // Drop bare blank lines (a lone empty field) — see the method doc.
        records.removeIf(r -> r.size() == 1 && r.get(0).isEmpty());
        return records;
    }
}
