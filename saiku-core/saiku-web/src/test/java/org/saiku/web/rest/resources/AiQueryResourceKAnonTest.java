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

    /** A matrix-format row: cells keyed by data-column INDEX (not caption). */
    private static Map<String, AiCell> matrixRow(double count, double sales) {
        Map<String, AiCell> r = new LinkedHashMap<>();
        r.put("0", new AiCell(count, String.valueOf((int) count), null)); // count column
        r.put("1", new AiCell(sales, "$" + sales, null)); // sales column
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

    /* --- #1324 gate: count-detection guard + matrix-gap characterization --- */

    @Test
    public void discount_alone_is_not_treated_as_the_count_column() {
        // saiku#905 / SEC+QA #1324 guard: "Discount" merely CONTAINS "count" —
        // it must NOT be mistaken for the row-count measure. Under the old
        // contains() heuristic this row (Discount=2 < k, no real count) was
        // masked off the discount value — suppression silently keyed off the
        // wrong column. With whole-word detection there is no count column here,
        // so nothing is suppressed. (Would go RED on the pre-fix heuristic.)
        AiQueryResource r = new AiQueryResource();
        r.setKAnonymityFilter(new KAnonymityFilter(5, "null"));

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("Product Family", "Specialty");
        rec.put("Discount", new AiCell(2.0, "2", null));
        rec.put("Store Sales", new AiCell(12.0, "$12", null));
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(rec);

        r.applyKAnonymity(records);

        assertFalse(
                "Discount must not be treated as a count column",
                ((AiCell) records.get(0).get("Store Sales")).isSuppressed());
        assertFalse(
                "the Discount cell itself must not be masked",
                ((AiCell) records.get(0).get("Discount")).isSuppressed());
    }

    @Test
    public void real_count_is_chosen_over_a_discount_decoy() {
        // A genuine "Fact Count" (=9, >= k) means the row is NOT small, even
        // though a decoy "Discount" (=2) sorts first in the row. The old
        // contains() pick would seize "Discount" first and wrongly suppress;
        // whole-word matching skips it and reads the real count → no suppression.
        AiQueryResource r = new AiQueryResource();
        r.setKAnonymityFilter(new KAnonymityFilter(5, "null"));

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("Product Family", "Mainline");
        rec.put("Discount", new AiCell(2.0, "2", null)); // decoy, sorts FIRST
        rec.put("Fact Count", new AiCell(9.0, "9", null)); // the real count, >= k
        rec.put("Store Sales", new AiCell(900.0, "$900", null));
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(rec);

        r.applyKAnonymity(records);

        assertFalse(
                "the real Fact Count (9 >= k) must drive the decision, not the Discount decoy",
                ((AiCell) records.get(0).get("Store Sales")).isSuppressed());
    }

    @Test
    public void matrix_output_is_now_suppressed() {
        // saiku#1324: the format=matrix egress path is no longer a bypass.
        // Matrix cells are index-keyed; applyKAnonymityMatrix locates the count
        // column from the index->caption list and masks every cell in a sub-k
        // row, exactly like the records path. (RED against the old #905-B no-op.)
        AiQueryResource r = new AiQueryResource();
        r.setKAnonymityFilter(new KAnonymityFilter(5, "null"));

        List<String> cols = List.of("Fact Count", "Store Sales"); // index 0, 1
        List<Map<String, AiCell>> matrix = new ArrayList<>();
        matrix.add(matrixRow(2, 12.0)); // count 2 < k -> suppressed
        matrix.add(matrixRow(9, 900.0)); // count 9 >= k -> kept

        r.applyKAnonymityMatrix(matrix, cols);

        assertTrue(matrix.get(0).get("1").isSuppressed());
        assertNull(matrix.get(0).get("1").getValue());
        assertTrue(
                "the count cell itself is disclosive and masked too",
                matrix.get(0).get("0").isSuppressed());
        assertFalse(matrix.get(1).get("1").isSuppressed());
        assertEquals(Double.valueOf(900.0), matrix.get(1).get("1").getValue());
    }

    @Test
    public void matrix_discount_decoy_is_not_the_count_column() {
        // Parity with the records guard: a "Discount" column must not be picked
        // as the count in matrix mode either (whole-word detection on captions).
        AiQueryResource r = new AiQueryResource();
        r.setKAnonymityFilter(new KAnonymityFilter(5, "null"));

        List<String> cols = List.of("Discount", "Store Sales");
        Map<String, AiCell> row = new LinkedHashMap<>();
        row.put("0", new AiCell(2.0, "2", null)); // Discount = 2 (decoy)
        row.put("1", new AiCell(12.0, "$12", null));
        List<Map<String, AiCell>> matrix = new ArrayList<>();
        matrix.add(row);

        r.applyKAnonymityMatrix(matrix, cols);

        assertFalse(
                "Discount must not be treated as the count column in matrix mode",
                matrix.get(0).get("1").isSuppressed());
    }
}
