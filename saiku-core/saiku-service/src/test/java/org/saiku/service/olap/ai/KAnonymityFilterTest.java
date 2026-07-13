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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.Test;

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
}
