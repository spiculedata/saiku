/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;

/**
 * Builds the markdown digest of a server-executed {@link CellDataSet}, matching the client-side
 * digest (saiku-ui/src/lib/api/cellsetDigest.ts) so the LLM reads server-run results in the exact
 * format it already understands from on-screen cellsets. Pure — no I/O, no olap4j.
 */
public final class CellsetDigestBuilder {

    private CellsetDigestBuilder() {}

    /** The redaction sentinel written in place of a suppressed cell. */
    public static final String REDACTED = "[REDACTED]";

    /**
     * saiku#1918 (17a) — what a {@link #digest} call is allowed to render verbatim.
     *
     * <p>The digest is the one place a server-executed cellset crosses to the LLM as text, so it
     * has to carry the same PII posture the query path does. {@link #redactRowHeaderColumn} is the
     * structural control: column 0 of every body row holds the ROW-AXIS member caption, which is
     * exactly where per-person data lives when a query is sliced by a
     * {@code saiku.semantic.pii=true} level. {@link #redactedColumnHeaders} is the finer-grained
     * control for a column whose header names a PII column.
     *
     * <p>The primary control is upstream — {@code AiSchemaConverter} refuses a PII measure or
     * level on any query axis (saiku#1918), so a converter-produced cellset can never reach this
     * class carrying a PII row axis. This policy is the second layer, for any path that executes a
     * cellset without going through the converter.
     */
    public record DigestPolicy(boolean redactRowHeaderColumn, java.util.Set<String> redactedColumnHeaders) {

        /** Render everything — the historical behaviour, and correct whenever no PII is in play. */
        public static final DigestPolicy NONE = new DigestPolicy(false, java.util.Set.of());

        public DigestPolicy {
            redactedColumnHeaders =
                    redactedColumnHeaders == null ? java.util.Set.of() : java.util.Set.copyOf(redactedColumnHeaders);
        }

        public static DigestPolicy redactRowHeader() {
            return new DigestPolicy(true, java.util.Set.of());
        }

        public static DigestPolicy redactColumnHeaders(java.util.Set<String> headers) {
            return new DigestPolicy(false, headers);
        }
    }

    /**
     * @param cds the executed cellset (may be null)
     * @param maxRows cap on data rows rendered (must be > 0)
     * @return the markdown digest, or "" when there is nothing useful to send
     */
    public static String digest(CellDataSet cds, int maxRows) {
        return digest(cds, maxRows, DigestPolicy.NONE);
    }

    /**
     * As {@link #digest(CellDataSet, int)} but honours a {@link DigestPolicy}. This is the entry
     * point the LLM-egress paths use, so a cellset that reached them by some route other than the
     * converter still cannot carry PII captions to the provider.
     *
     * @param cds the executed cellset (may be null)
     * @param maxRows cap on data rows rendered (must be > 0)
     * @param policy which columns to redact; {@code null} is treated as {@link DigestPolicy#NONE}
     * @return the markdown digest, or "" when there is nothing useful to send
     */
    public static String digest(CellDataSet cds, int maxRows, DigestPolicy policy) {
        if (cds == null) return "";
        DigestPolicy p = policy == null ? DigestPolicy.NONE : policy;
        AbstractBaseCell[][] body = cds.getCellSetBody();
        if (body == null || body.length == 0) return "";
        AbstractBaseCell[][] headers = cds.getCellSetHeaders();
        if (headers == null) headers = new AbstractBaseCell[0][];

        int bodyRowCount = body.length;
        int colCount = 0;
        for (AbstractBaseCell[] r : headers) colCount = Math.max(colCount, r == null ? 0 : r.length);
        for (AbstractBaseCell[] r : body) colCount = Math.max(colCount, r == null ? 0 : r.length);
        if (colCount == 0) return "";

        final boolean redactRowHeader = p.redactRowHeaderColumn();
        int[] redactColumns = redactedColumnIndices(headers, colCount, p.redactedColumnHeaders());

        boolean truncated = bodyRowCount > maxRows;
        StringBuilder sb = new StringBuilder();
        sb.append("Cellset: ")
                .append(bodyRowCount)
                .append(" data rows × ")
                .append(colCount)
                .append(" columns.\n");
        if (truncated) {
            sb.append("(Showing first ")
                    .append(maxRows)
                    .append(" of ")
                    .append(bodyRowCount)
                    .append(" rows.)\n");
        }
        sb.append("\n");

        for (AbstractBaseCell[] row : headers) {
            sb.append(formatRow(row, colCount, redactRowHeader, redactColumns)).append("\n");
        }
        if (headers.length > 0) {
            sb.append("| ");
            for (int i = 0; i < colCount; i++) {
                sb.append("---");
                if (i < colCount - 1) sb.append(" | ");
            }
            sb.append(" |\n");
        }
        int limit = Math.min(bodyRowCount, maxRows);
        for (int i = 0; i < limit; i++) {
            sb.append(formatRow(body[i], colCount, redactRowHeader, redactColumns))
                    .append("\n");
        }
        // Trim the trailing newline to match the client's join("\n") (no trailing newline).
        return sb.toString().stripTrailing();
    }

    /** Columns whose header text names a PII column. Empty array when none do. */
    private static int[] redactedColumnIndices(
            AbstractBaseCell[][] headers, int colCount, java.util.Set<String> piiHeaders) {
        if (piiHeaders.isEmpty()) return new int[0];
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (AbstractBaseCell[] row : headers) {
            if (row == null) continue;
            for (int i = 0; i < Math.min(colCount, row.length); i++) {
                String text = cellText(row[i]);
                if (text.isEmpty()) continue;
                for (String h : piiHeaders) {
                    if (h != null && h.equalsIgnoreCase(text)) {
                        if (!out.contains(i)) out.add(i);
                        break;
                    }
                }
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static boolean isRedacted(int col, boolean redactRowHeader, int[] redactColumns) {
        if (redactRowHeader && col == 0) return true;
        for (int c : redactColumns) {
            if (c == col) return true;
        }
        return false;
    }

    private static String formatRow(AbstractBaseCell[] row, int width, boolean redactRowHeader, int[] redactColumns) {
        StringBuilder sb = new StringBuilder("| ");
        for (int i = 0; i < width; i++) {
            AbstractBaseCell cell = (row != null && i < row.length) ? row[i] : null;
            sb.append(isRedacted(i, redactRowHeader, redactColumns) ? REDACTED : cellText(cell));
            if (i < width - 1) sb.append(" | ");
        }
        sb.append(" |");
        return sb.toString();
    }

    private static String cellText(AbstractBaseCell cell) {
        if (cell == null) return "";
        String v = cell.getFormattedValue();
        if (v == null) v = cell.getRawValue();
        if (v == null) return "";
        return v.replace('|', '/').replaceAll("\\s+", " ").trim();
    }
}
