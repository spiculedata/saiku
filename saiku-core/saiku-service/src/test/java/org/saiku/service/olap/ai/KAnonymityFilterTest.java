/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;

/** saiku#905 — unit coverage for k-anonymity small-cell suppression. */
public class KAnonymityFilterTest {

    private static Function<String, String> map(String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m::get;
    }

    /* ----------------------------- config ---------------------------- */

    @Test
    public void resolveThreshold_env_beats_prop_beats_default() {
        assertEquals(8, KAnonymityFilter.resolveThreshold(map(KAnonymityFilter.ENV_K, "8"), map()));
        assertEquals(3, KAnonymityFilter.resolveThreshold(map(), map(KAnonymityFilter.PROP_K, "3")));
        assertEquals(KAnonymityFilter.DEFAULT_K, KAnonymityFilter.resolveThreshold(map(), map()));
        assertEquals(0, KAnonymityFilter.resolveThreshold(map(), map(KAnonymityFilter.PROP_K, "0")));
    }

    @Test
    public void resolveThreshold_invalid_fails_safe_to_default() {
        assertEquals(
                KAnonymityFilter.DEFAULT_K,
                KAnonymityFilter.resolveThreshold(map(), map(KAnonymityFilter.PROP_K, "abc")));
        assertEquals(
                KAnonymityFilter.DEFAULT_K,
                KAnonymityFilter.resolveThreshold(map(), map(KAnonymityFilter.PROP_K, "-4")));
    }

    @Test
    public void resolveMask_defaults_to_null_token() {
        assertEquals("null", KAnonymityFilter.resolveMask(map(), map()));
        assertEquals("REDACTED", KAnonymityFilter.resolveMask(map(), map(KAnonymityFilter.PROP_MASK, "REDACTED")));
        assertEquals(
                "-1",
                KAnonymityFilter.resolveMask(
                        map(KAnonymityFilter.ENV_MASK, "-1"), map(KAnonymityFilter.PROP_MASK, "REDACTED")));
    }

    /* --------------------------- decisions --------------------------- */

    @Test
    public void enabled_only_when_threshold_positive() {
        assertTrue(new KAnonymityFilter(5, null).enabled());
        assertFalse(new KAnonymityFilter(0, null).enabled());
    }

    @Test
    public void shouldSuppress_is_below_k_inclusive_boundary() {
        KAnonymityFilter f = new KAnonymityFilter(5, null);
        assertTrue(f.shouldSuppress(3));
        assertTrue(f.shouldSuppress(1));
        assertFalse("k itself is not masked (inclusive)", f.shouldSuppress(5));
        assertFalse(f.shouldSuppress(6));
        assertFalse("unknown/zero count is not masked", f.shouldSuppress(0));
        assertFalse(new KAnonymityFilter(0, null).shouldSuppress(2));
    }

    /* ----------------------------- mask ------------------------------ */

    @Test
    public void mask_null_token_clears_value_and_flags() {
        AiCell c = new AiCell(42.0, "42", null);
        new KAnonymityFilter(5, "null").mask(c);
        assertTrue(c.isSuppressed());
        assertNull(c.getValue());
        assertEquals("—", c.getFormatted());
    }

    @Test
    public void mask_numeric_token_sets_that_value() {
        AiCell c = new AiCell(42.0, "42", null);
        new KAnonymityFilter(5, "-1").mask(c);
        assertTrue(c.isSuppressed());
        assertEquals(Double.valueOf(-1.0), c.getValue());
        assertEquals("-1", c.getFormatted());
    }

    @Test
    public void mask_text_token_shows_token_no_value() {
        AiCell c = new AiCell(42.0, "42", null);
        new KAnonymityFilter(5, "REDACTED").mask(c);
        assertTrue(c.isSuppressed());
        assertNull(c.getValue());
        assertEquals("REDACTED", c.getFormatted());
    }

    /* ------------------------- applyToRecords ------------------------ */

    private static Map<String, Object> row(String dim, double count, double sales) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Product", dim);
        r.put("Fact Count", new AiCell(count, String.valueOf((int) count), null));
        r.put("Store Sales", new AiCell(sales, "$" + sales, null));
        return r;
    }

    @Test
    public void applyToRecords_masks_small_rows_keeps_the_rest() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Tiny", 3, 99.0)); // below k=5 -> masked
        rows.add(row("Big", 5, 500.0)); // exactly k -> kept
        List<String> measures = java.util.Arrays.asList("Fact Count", "Store Sales");

        int n = new KAnonymityFilter(5, "null").applyToRecords(rows, "Fact Count", measures);

        assertEquals(1, n);
        // small row: both measure cells masked (incl. the count cell itself)
        assertTrue(((AiCell) rows.get(0).get("Store Sales")).isSuppressed());
        assertNull(((AiCell) rows.get(0).get("Store Sales")).getValue());
        assertTrue(((AiCell) rows.get(0).get("Fact Count")).isSuppressed());
        // row-header String untouched
        assertEquals("Tiny", rows.get(0).get("Product"));
        // big row: intact
        assertFalse(((AiCell) rows.get(1).get("Store Sales")).isSuppressed());
        assertEquals(Double.valueOf(500.0), ((AiCell) rows.get(1).get("Store Sales")).getValue());
    }

    @Test
    public void applyToRecords_disabled_is_noop() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Tiny", 1, 10.0));
        int n = new KAnonymityFilter(0, "null").applyToRecords(rows, "Fact Count", java.util.List.of("Store Sales"));
        assertEquals(0, n);
        assertFalse(((AiCell) rows.get(0).get("Store Sales")).isSuppressed());
    }

    /* ------------------------ applyToCellDataSet ---------------------- */

    private static MemberCell header(String value) {
        MemberCell cell = new MemberCell(false, false);
        cell.setFormattedValue(value);
        return cell;
    }

    private static DataCell data(String value) {
        DataCell cell = new DataCell(false, false, Collections.emptyList());
        cell.setFormattedValue(value);
        cell.setRawNumber(Double.valueOf(value.replace(",", "")));
        return cell;
    }

    private static MemberCell rowHeader(String value) {
        MemberCell cell = new MemberCell(false, false);
        cell.setFormattedValue(value);
        return cell;
    }

    /** Grid: [Product(header) | Count | Balance], one small row (count 3) and one big row (count 10). */
    private static CellDataSet grid() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Product"), header("Count"), header("Balance")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {
            {rowHeader("Tiny"), data("3"), data("99")},
            {rowHeader("Big"), data("10"), data("5,000")}
        });
        return cds;
    }

    @Test
    public void applyToCellDataSet_masks_subK_row_including_count_cell() {
        CellDataSet cds = grid();
        int n = new KAnonymityFilter(5, "null").applyToCellDataSet(cds);

        assertEquals(1, n);
        AbstractBaseCell[] tiny = cds.getCellSetBody()[0];
        assertEquals("row-header untouched", "Tiny", tiny[0].getFormattedValue());
        assertEquals("—", tiny[1].getFormattedValue()); // count cell itself masked
        assertEquals("—", tiny[2].getFormattedValue()); // Balance masked
        assertNull(((DataCell) tiny[1]).getRawNumber());
        assertNull(((DataCell) tiny[2]).getRawNumber());
    }

    @Test
    public void applyToCellDataSet_leaves_atOrAboveK_row_untouched() {
        CellDataSet cds = grid();
        new KAnonymityFilter(5, "null").applyToCellDataSet(cds);

        AbstractBaseCell[] big = cds.getCellSetBody()[1];
        assertEquals("Big", big[0].getFormattedValue());
        assertEquals("10", big[1].getFormattedValue());
        assertEquals("5,000", big[2].getFormattedValue());
    }

    @Test
    public void applyToCellDataSet_noCountColumn_isNoop() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Product"), header("Balance")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {{rowHeader("Tiny"), data("99")}});

        int n = new KAnonymityFilter(5, "null").applyToCellDataSet(cds);

        assertEquals(0, n);
        assertEquals("99", cds.getCellSetBody()[0][1].getFormattedValue());
    }

    @Test
    public void applyToCellDataSet_disabled_isNoop() {
        CellDataSet cds = grid();
        int n = new KAnonymityFilter(0, "null").applyToCellDataSet(cds);

        assertEquals(0, n);
        assertEquals("3", cds.getCellSetBody()[0][1].getFormattedValue());
    }

    /**
     * saiku SEC re-review — the AI query converter always nests measures ABOVE any column
     * dimension, so a multi-level column axis (measures outer, a dimension inner) puts DIMENSION
     * members ("1997", "1998") in the LAST header row, not the measure captions. Reading only the
     * last header row (the pre-fix behaviour) therefore never finds "Fact Count" and the count
     * column is missed entirely -> countCol stays -1 -> applyToCellDataSet no-ops -> a sub-k row
     * reaches the LLM unmasked. The fix joins ALL header rows with fill-down (mirroring
     * AiQueryResource.buildResponse) so "Fact Count | 1997" is visible on the joined caption.
     *
     * <p>Layout (col 0 = row header):
     * <pre>
     * row0 (measures, spanned): ["",           "Fact Count", "",     "Store Sales", ""]
     * row1 (inner dimension):   ["",           "1997",       "1998", "1997",        "1998"]
     * body (Acme):              [Acme,         2,            9,      100,           100]
     * body (BigCo):             [BigCo,        10,           1,      500,           600]
     * </pre>
     * With k=5: the located count column is col 1 ("Fact Count | 1997") — single-count-column
     * semantics, same as {@link #applyToMatrix}. Acme's col-1 count (2) is below k, so its whole
     * row is masked (cols 1-4), including col 2 even though the "Fact Count | 1998" figure (9) is
     * itself &gt;= k — masking is keyed off the ONE located count column, not per measure. BigCo's
     * col-1 count (10) is &gt;= k, so it stays untouched even though its col-2 count (1) is small —
     * that's the documented single-countKey limitation, not a bug this fix introduces.
     */
    private static CellDataSet multiLevelColumnAxisGrid() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {
            {header(""), header("Fact Count"), header(""), header("Store Sales"), header("")},
            {header(""), header("1997"), header("1998"), header("1997"), header("1998")}
        });
        cds.setCellSetBody(new AbstractBaseCell[][] {
            {rowHeader("Acme"), data("2"), data("9"), data("100"), data("100")},
            {rowHeader("BigCo"), data("10"), data("1"), data("500"), data("600")}
        });
        return cds;
    }

    @Test
    public void applyToCellDataSet_findsCountColumn_acrossMultiLevelColumnAxis() {
        CellDataSet cds = multiLevelColumnAxisGrid();
        int n = new KAnonymityFilter(5, "null").applyToCellDataSet(cds);

        assertEquals("only Acme's row (col-1 count=2 < k=5) is suppressed", 1, n);

        AbstractBaseCell[] acme = cds.getCellSetBody()[0];
        assertEquals("row-header untouched", "Acme", acme[0].getFormattedValue());
        assertEquals("—", acme[1].getFormattedValue());
        assertEquals("—", acme[2].getFormattedValue());
        assertEquals("—", acme[3].getFormattedValue());
        assertEquals("—", acme[4].getFormattedValue());
        assertNull(((DataCell) acme[1]).getRawNumber());
        assertNull(((DataCell) acme[4]).getRawNumber());

        AbstractBaseCell[] bigCo = cds.getCellSetBody()[1];
        assertEquals(
                "BigCo's col-1 count (10) is >= k, so the row stays untouched "
                        + "(single countKey semantics, same as applyToMatrix)",
                "10",
                bigCo[1].getFormattedValue());
        assertEquals("1", bigCo[2].getFormattedValue());
        assertEquals("500", bigCo[3].getFormattedValue());
        assertEquals("600", bigCo[4].getFormattedValue());
    }

    @Test
    public void applyToCellDataSet_emptyBody_isNoop() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetBody(new AbstractBaseCell[][] {});
        assertEquals(0, new KAnonymityFilter(5, "null").applyToCellDataSet(cds));

        CellDataSet cdsNullBody = new CellDataSet();
        assertEquals(0, new KAnonymityFilter(5, "null").applyToCellDataSet(cdsNullBody));

        assertEquals(0, new KAnonymityFilter(5, "null").applyToCellDataSet(null));
    }
}
