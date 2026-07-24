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

    /**
     * @param cds the executed cellset (may be null)
     * @param maxRows cap on data rows rendered (must be &gt; 0)
     * @return the markdown digest, or "" when there is nothing useful to send
     */
    public static String digest(CellDataSet cds, int maxRows) {
        if (cds == null) return "";
        AbstractBaseCell[][] body = cds.getCellSetBody();
        if (body == null || body.length == 0) return "";
        AbstractBaseCell[][] headers = cds.getCellSetHeaders();
        if (headers == null) headers = new AbstractBaseCell[0][];

        int bodyRowCount = body.length;
        int colCount = 0;
        for (AbstractBaseCell[] r : headers) colCount = Math.max(colCount, r == null ? 0 : r.length);
        for (AbstractBaseCell[] r : body) colCount = Math.max(colCount, r == null ? 0 : r.length);
        if (colCount == 0) return "";

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
            sb.append(formatRow(row, colCount)).append("\n");
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
            sb.append(formatRow(body[i], colCount)).append("\n");
        }
        // Trim the trailing newline to match the client's join("\n") (no trailing newline).
        return sb.toString().stripTrailing();
    }

    private static String formatRow(AbstractBaseCell[] row, int width) {
        StringBuilder sb = new StringBuilder("| ");
        for (int i = 0; i < width; i++) {
            AbstractBaseCell cell = (row != null && i < row.length) ? row[i] : null;
            sb.append(cellText(cell));
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
