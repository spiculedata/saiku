/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;

/**
 * Unit tests for the cellset → records flattening in {@link LiveEvalAskAdapter}. The full
 * ask/convert/execute pipeline is covered by the resource-level integration path — this focuses on
 * the extraction that turns Mondrian's cellset shape into the neutral {@code List<Map<String,
 * Object>>} the eval framework compares.
 */
public class LiveEvalAskAdapterTest {

    @Test
    public void nullCellsetProducesEmptyList() {
        assertTrue(LiveEvalAskAdapter.flattenToRecords(null).isEmpty());
    }

    @Test
    public void emptyBodyProducesEmptyList() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetBody(new AbstractBaseCell[0][0]);
        assertTrue(LiveEvalAskAdapter.flattenToRecords(cds).isEmpty());
    }

    @Test
    public void flattensSingleAxisQueryToRecords() {
        // "Show store sales by country" — 1 row-header column (Country) + 1 data column (Store Sales).
        CellDataSet cds = buildCellset(new String[][] {{"Country", "Store Sales"}}, new Cell[][] {
            {member("USA"), data("$565,238.13", 565238.13)},
            {member("Canada"), data("$79,063.11", 79063.11)},
            {member("Mexico"), data("$51,298.13", 51298.13)}
        });

        List<Map<String, Object>> rows = LiveEvalAskAdapter.flattenToRecords(cds);
        assertEquals(3, rows.size());

        assertEquals("USA", rows.get(0).get("Country"));
        assertEquals(565238.13, ((Number) rows.get(0).get("Store Sales")).doubleValue(), 1e-9);

        assertEquals("Canada", rows.get(1).get("Country"));
        assertEquals(79063.11, ((Number) rows.get(1).get("Store Sales")).doubleValue(), 1e-9);
    }

    @Test
    public void preservesNumericRawValueWhenAvailable() {
        // Data cells with a raw number should preserve their numeric type — the comparator's
        // tolerance math needs doubles, not strings, or every eval degrades to string-eq mode.
        CellDataSet cds = buildCellset(
                new String[][] {{"Country", "Unit Sales"}}, new Cell[][] {{member("USA"), data("266,773", 266773)}});
        Map<String, Object> row = LiveEvalAskAdapter.flattenToRecords(cds).get(0);
        assertTrue("data column value must be numeric", row.get("Unit Sales") instanceof Number);
    }

    @Test
    public void fallsBackToFormattedStringWhenRawIsNull() {
        // Text-only data cell (no raw number available) — falls back to the formatted string.
        CellDataSet cds = buildCellset(
                new String[][] {{"Country", "Comment"}}, new Cell[][] {{member("USA"), data("north-american", null)}});
        Map<String, Object> row = LiveEvalAskAdapter.flattenToRecords(cds).get(0);
        assertEquals("north-american", row.get("Comment"));
    }

    @Test
    public void handlesMultipleRowHeaderColumns() {
        // Two dims on rows (Country × Year), one measure column.
        CellDataSet cds = buildCellset(new String[][] {{"Country", "Year", "Store Sales"}}, new Cell[][] {
            {member("USA"), member("1997"), data("$200K", 200000.0)},
            {member("USA"), member("1998"), data("$225K", 225000.0)}
        });

        List<Map<String, Object>> rows = LiveEvalAskAdapter.flattenToRecords(cds);
        assertEquals(2, rows.size());
        assertEquals("USA", rows.get(0).get("Country"));
        assertEquals("1997", rows.get(0).get("Year"));
        assertNotNull(rows.get(0).get("Store Sales"));
    }

    @Test
    public void generatesSyntheticCaptionsWhenHeadersMissing() {
        // Body-only cellset (e.g. tests that don't bother building headers). The extractor
        // shouldn't crash — it uses synthetic row0/col0 keys.
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[0][0]);
        cds.setCellSetBody(new AbstractBaseCell[][] {
            {member("Row").cell(), data("100", 100).cell()}
        });
        List<Map<String, Object>> rows = LiveEvalAskAdapter.flattenToRecords(cds);
        assertEquals(1, rows.size());
        // No row-header captions were provided → the extractor treats every column as a data column
        // (rowHeaderCount = 0 when the body's leftmost column contains a MemberCell but there are
        // no headers to name it). Just verify we produced a row without crashing.
        assertNotNull(rows.get(0));
    }

    /* ---------- test-cellset builder ---------- */

    /** Sugar wrapper for a body cell — either a member (row header) or a data cell. */
    private record Cell(AbstractBaseCell cell) {}

    private static Cell member(String caption) {
        MemberCell m = new MemberCell();
        m.setFormattedValue(caption);
        return new Cell(m);
    }

    private static Cell data(String formatted, Number raw) {
        DataCell d = new DataCell();
        d.setFormattedValue(formatted);
        if (raw != null) d.setRawNumber(raw.doubleValue());
        return new Cell(d);
    }

    private static CellDataSet buildCellset(String[][] headerCaptions, Cell[][] body) {
        CellDataSet cds = new CellDataSet();
        // Header rows: one row per level, one column per body column. Populate every cell as a
        // MemberCell so the extractor sees a proper header shape.
        AbstractBaseCell[][] headers = new AbstractBaseCell[headerCaptions.length][];
        for (int r = 0; r < headerCaptions.length; r++) {
            headers[r] = new AbstractBaseCell[headerCaptions[r].length];
            for (int c = 0; c < headerCaptions[r].length; c++) {
                MemberCell m = new MemberCell();
                m.setFormattedValue(headerCaptions[r][c]);
                headers[r][c] = m;
            }
        }
        cds.setCellSetHeaders(headers);

        AbstractBaseCell[][] bodyCells = new AbstractBaseCell[body.length][];
        for (int r = 0; r < body.length; r++) {
            bodyCells[r] = new AbstractBaseCell[body[r].length];
            for (int c = 0; c < body[r].length; c++) {
                bodyCells[r][c] = body[r][c].cell();
            }
        }
        cds.setCellSetBody(bodyCells);
        return cds;
    }
}
