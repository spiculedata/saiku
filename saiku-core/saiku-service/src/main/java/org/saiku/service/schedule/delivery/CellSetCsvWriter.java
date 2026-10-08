/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.delivery;

import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;

/**
 * Flattens an executed {@link CellDataSet} into RFC-4180 CSV (saiku#1987) — the bytes the
 * {@code SAVED_QUERY_CSV} producer hands to a destination.
 *
 * <p>Hand-rolled rather than pulled from a CSV library because the shape is fixed (a header block, a
 * body block, both {@code AbstractBaseCell[][]}) and the security-relevant part is small enough to
 * read: <b>a leading {@code =}, {@code +}, {@code -} or {@code @} in a value is prefixed with a
 * single quote.</b> An export routinely lands in a spreadsheet, and an unescaped cell value is a
 * formula-injection vector into whoever opens the file. That prefix is the standard mitigation and
 * costs one character in the common case.
 */
public final class CellSetCsvWriter {

    private static final char[] NEEDS_QUOTING = {',', '"', '\n', '\r'};
    private static final String LINE_SEP = "\n";

    private CellSetCsvWriter() {}

    /** The cell set as UTF-8 CSV bytes, with a UTF-8 BOM so Excel opens non-ASCII exports correctly. */
    public static byte[] toCsvBytes(CellDataSet cellSet) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, cellSet == null ? null : cellSet.getCellSetHeaders());
        appendRow(sb, cellSet == null ? null : cellSet.getCellSetBody());
        byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private static void appendRow(StringBuilder sb, AbstractBaseCell[][] row) {
        if (row == null) {
            return;
        }
        for (AbstractBaseCell[] cells : row) {
            if (cells == null) {
                sb.append(LINE_SEP);
                continue;
            }
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escape(value(cells[i])));
            }
            sb.append(LINE_SEP);
        }
    }

    private static String value(AbstractBaseCell cell) {
        if (cell == null) {
            return "";
        }
        String formatted = cell.getFormattedValue();
        return formatted != null ? formatted : (cell.getRawValue() == null ? "" : cell.getRawValue());
    }

    /** Quote per RFC-4180, and defuse a leading spreadsheet formula character. */
    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw;
        char first = raw.isEmpty() ? 0 : raw.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            value = "'" + value;
        }
        boolean quote = false;
        for (char c : NEEDS_QUOTING) {
            quote |= value.indexOf(c) >= 0;
        }
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** Test helper: the CSV as a string, without the BOM. */
    public static String toCsvString(CellDataSet cellSet) {
        byte[] bytes = toCsvBytes(cellSet);
        int offset =
                bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF
                        ? 3
                        : 0;
        return new String(bytes, offset, bytes.length - offset, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The number of CSV lines {@code toCsvString} would produce — handy in assertions. */
    public static int lineCount(CellDataSet cellSet) {
        String csv = toCsvString(cellSet);
        return csv.isEmpty() ? 0 : (int) csv.lines().count();
    }
}
