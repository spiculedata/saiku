/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCell;
import org.saiku.service.olap.ai.KAnonymityFilter;

/**
 * The real k-anonymity guarantee (beyond the {@code shouldSuppress} predicate): after
 * {@link KAnonymityFilter#applyToMatrix}, <b>no</b> row backed by a known sub-k count may keep an
 * unmasked measure value, and <b>every</b> row at or above k must be left exactly as it was. This
 * is the property that actually protects data at the AI egress — and the exact class of gap
 * (records/matrix drift) that let sub-k rows leak before.
 */
class KAnonymityFilterMatrixPropertyTest {

    private static final String COUNT = "count";
    private static final List<String> MEASURES = List.of(COUNT, "m1", "m2");
    private static final double M1 = 123.0;
    private static final double M2 = 456.0;

    @HegelTest
    void subKRowsMaskedAndSafeRowsUntouched(TestCase tc) {
        int k = tc.draw(integers().map(n -> 2 + Math.floorMod(n, 20)), "k"); // [2, 21]
        int numRows = tc.draw(integers().map(n -> Math.floorMod(n, 8)), "numRows"); // [0, 7]

        List<Map<String, AiCell>> rows = new ArrayList<>();
        List<Integer> originalCounts = new ArrayList<>();
        for (int i = 0; i < numRows; i++) {
            // Span the whole decision space: 0 (unknown), [1,k) (mask), and >= k (keep).
            int count = tc.draw(integers().map(n -> Math.floorMod(n, 3 * k)), "count" + i); // [0, 3k)
            originalCounts.add(count);

            Map<String, AiCell> row = new HashMap<>();
            row.put(COUNT, new AiCell((double) count, Integer.toString(count), "rows"));
            row.put("m1", new AiCell(M1, "123", "gbp"));
            row.put("m2", new AiCell(M2, "456", "gbp"));
            rows.add(row);
        }

        new KAnonymityFilter(k, "null").applyToMatrix(rows, COUNT, MEASURES);

        for (int i = 0; i < numRows; i++) {
            int count = originalCounts.get(i);
            boolean shouldMask = count > 0 && count < k;
            AiCell m1 = rows.get(i).get("m1");
            AiCell m2 = rows.get(i).get("m2");
            AiCell countCell = rows.get(i).get(COUNT);

            if (shouldMask) {
                assertTrue(m1.isSuppressed(), "sub-k row (count=" + count + ", k=" + k + ") m1 must be masked");
                assertTrue(m2.isSuppressed(), "sub-k row (count=" + count + ", k=" + k + ") m2 must be masked");
                assertTrue(countCell.isSuppressed(), "the disclosive count cell itself must be masked");
                assertNull(m1.getValue(), "masked value must be nulled (maskValue=null)");
            } else {
                assertFalse(m1.isSuppressed(), "row with count=" + count + " (k=" + k + ") must NOT be masked");
                assertFalse(m2.isSuppressed(), "row with count=" + count + " (k=" + k + ") must NOT be masked");
                assertEquals(M1, m1.getValue(), "unmasked measure value must be preserved exactly");
            }
        }
    }
}
