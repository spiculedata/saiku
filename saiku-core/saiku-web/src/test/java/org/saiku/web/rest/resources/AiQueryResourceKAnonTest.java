/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCell;
import org.saiku.service.olap.ai.KAnonymityFilter;

/**
 * saiku#905 — call-site (wiring) coverage for {@code AiQueryResource.applyKAnonymity}:
 * proves the resource detects the in-result count measure ("...count...") and
 * runs suppression over the records payload, not just that the filter works in
 * isolation.
 */
public class AiQueryResourceKAnonTest {

    private static Map<String, Object> row(String dim, double count, double sales) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Product Family", dim);
        r.put("Fact Count", new AiCell(count, String.valueOf((int) count), null));
        r.put("Store Sales", new AiCell(sales, "$" + sales, null));
        return r;
    }

    @Test
    public void detects_count_column_and_suppresses_small_rows() {
        AiQueryResource r = new AiQueryResource();
        r.setKAnonymityFilter(new KAnonymityFilter(5, "null"));

        List<Map<String, Object>> records = new ArrayList<>();
        records.add(row("Specialty", 2, 12.0)); // < 5 -> suppressed
        records.add(row("Mainline", 9, 900.0)); // >= 5 -> kept

        r.applyKAnonymity(records);

        assertTrue(((AiCell) records.get(0).get("Store Sales")).isSuppressed());
        assertNull(((AiCell) records.get(0).get("Store Sales")).getValue());
        assertTrue(((AiCell) records.get(0).get("Fact Count")).isSuppressed());
        assertFalse(((AiCell) records.get(1).get("Store Sales")).isSuppressed());
        assertEquals(Double.valueOf(900.0), ((AiCell) records.get(1).get("Store Sales")).getValue());
    }

    @Test
    public void noop_when_no_count_column_present() {
        AiQueryResource r = new AiQueryResource();
        r.setKAnonymityFilter(new KAnonymityFilter(5, "null"));

        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("Product Family", "Specialty");
        rec.put("Store Sales", new AiCell(12.0, "$12", null)); // no count column
        records.add(rec);

        r.applyKAnonymity(records);

        assertFalse(
                "cannot suppress without a count measure",
                ((AiCell) records.get(0).get("Store Sales")).isSuppressed());
    }

    @Test
    public void unwired_filter_defaults_disabled() {
        // No setKAnonymityFilter() -> default k=0 (disabled) -> no suppression,
        // preserving existing behaviour until the Spring bean injects the real one.
        AiQueryResource r = new AiQueryResource();
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(row("Specialty", 1, 5.0));
        r.applyKAnonymity(records);
        assertFalse(((AiCell) records.get(0).get("Store Sales")).isSuppressed());
    }
}
