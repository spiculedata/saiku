/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RowComparatorTest {

    private static final EvalTolerance EXACT = EvalTolerance.EXACT;
    private static final EvalTolerance ONE_PCT = new EvalTolerance(0.0, 0.01);

    @Test
    public void emptyExpectedMeansPass() {
        assertTrue(RowComparator.compare(null, someRows(), EXACT, true).isEmpty());
        assertTrue(RowComparator.compare(List.of(), someRows(), EXACT, true).isEmpty());
    }

    @Test
    public void exactMatchPasses() {
        List<Map<String, Object>> exp = List.of(row("country", "USA", "storeSales", 500.0));
        List<Map<String, Object>> act = List.of(row("country", "USA", "storeSales", 500.0));
        assertTrue(RowComparator.compare(exp, act, EXACT, true).isEmpty());
    }

    @Test
    public void toleranceMasksSmallNumericDrift() {
        List<Map<String, Object>> exp = List.of(row("storeSales", 500.0));
        List<Map<String, Object>> act = List.of(row("storeSales", 501.0)); // 0.2% drift
        // Exact fails, 1% relative tolerance passes.
        assertEquals(1, RowComparator.compare(exp, act, EXACT, true).size());
        assertTrue(RowComparator.compare(exp, act, ONE_PCT, true).isEmpty());
    }

    @Test
    public void rowCountMismatchIsReported() {
        List<Map<String, Object>> exp = List.of(row("k", "a"), row("k", "b"), row("k", "c"));
        List<Map<String, Object>> act = List.of(row("k", "a"), row("k", "b"));
        var mismatches = RowComparator.compare(exp, act, EXACT, true);
        assertTrue(mismatches.stream()
                .anyMatch(m -> m.path().equals("rows") && m.message().contains("3")));
    }

    @Test
    public void keyNormalisationEqualsRenameVariants() {
        // "Store Sales" == "storeSales" == "STORE_SALES" all normalise to the same key.
        List<Map<String, Object>> exp = List.of(row("Store Sales", 500.0));
        List<Map<String, Object>> act = List.of(row("storeSales", 500.0));
        assertTrue(RowComparator.compare(exp, act, EXACT, true).isEmpty());

        List<Map<String, Object>> upper = List.of(row("STORE_SALES", 500.0));
        assertTrue(RowComparator.compare(exp, upper, EXACT, true).isEmpty());
    }

    @Test
    public void missingKeyIsMismatch() {
        List<Map<String, Object>> exp = List.of(row("country", "USA", "storeSales", 500.0));
        List<Map<String, Object>> act = List.of(row("country", "USA")); // no storeSales
        var mismatches = RowComparator.compare(exp, act, EXACT, true);
        assertTrue(mismatches.stream().anyMatch(m -> m.path().contains("storeSales")));
    }

    @Test
    public void extraActualKeyIsNotMismatch() {
        // Expectations are additive. An actual row with an extra column doesn't fail the eval.
        List<Map<String, Object>> exp = List.of(row("country", "USA"));
        List<Map<String, Object>> act = List.of(row("country", "USA", "extra", "sidecar"));
        assertTrue(RowComparator.compare(exp, act, EXACT, true).isEmpty());
    }

    @Test
    public void orderMattersFalseSortsBothSides() {
        List<Map<String, Object>> exp = List.of(row("country", "USA", "n", 3.0), row("country", "Canada", "n", 1.0));
        List<Map<String, Object>> act = List.of(row("country", "Canada", "n", 1.0), row("country", "USA", "n", 3.0));
        // With orderMatters=true, positions mismatch.
        var strict = RowComparator.compare(exp, act, EXACT, true);
        assertTrue("strict order should surface mismatches", !strict.isEmpty());
        // With orderMatters=false, sort makes them equal.
        assertTrue(RowComparator.compare(exp, act, EXACT, false).isEmpty());
    }

    @Test
    public void currencyPrefixesAndCommasParseAsNumbers() {
        // Eval author writes "$565,238.13" as string; comparator parses it as 565238.13.
        List<Map<String, Object>> exp = List.of(row("storeSales", "$565,238.13"));
        List<Map<String, Object>> act = List.of(row("storeSales", 565238.13));
        assertTrue(RowComparator.compare(exp, act, EXACT, true).isEmpty());
    }

    @Test
    public void parenthesisedNegativeParses() {
        List<Map<String, Object>> exp = List.of(row("delta", "(500)"));
        List<Map<String, Object>> act = List.of(row("delta", -500.0));
        assertTrue(RowComparator.compare(exp, act, EXACT, true).isEmpty());
    }

    @Test
    public void stringComparisonNormalisesWhitespace() {
        List<Map<String, Object>> exp = List.of(row("country", "USA "));
        List<Map<String, Object>> act = List.of(row("country", " USA"));
        assertTrue(RowComparator.compare(exp, act, EXACT, true).isEmpty());
    }

    @Test
    public void mismatchPathIsPreciseForFailedCell() {
        List<Map<String, Object>> exp = List.of(row("k", "a"), row("k", "b", "n", 100.0));
        List<Map<String, Object>> act = List.of(row("k", "a"), row("k", "b", "n", 250.0));
        var mismatches = RowComparator.compare(exp, act, EXACT, true);
        assertEquals(1, mismatches.size());
        assertEquals("rows[1].n", mismatches.get(0).path());
        assertTrue(mismatches.get(0).message().contains("100.0"));
        assertTrue(mismatches.get(0).message().contains("250.0"));
    }

    /* ---- helpers ---- */

    private static List<Map<String, Object>> someRows() {
        return List.of(row("k", "value"));
    }

    private static Map<String, Object> row(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
