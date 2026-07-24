/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.saiku.service.olap.ai.KAnonymityFilter;

/**
 * Privacy boundary for {@link KAnonymityFilter#shouldSuppress(int)} — the k-anonymity gate that
 * masks small, disclosive result cells before they leave the AI query surface. The documented
 * contract has two easy-to-get-wrong edges: a count of exactly {@code k} is NOT masked (inclusive),
 * and an unknown count ({@code <= 0}) is never masked. These properties lock all three regions
 * across the full int space instead of a few examples.
 */
class KAnonymityFilterPropertyTest {

    private static int kBetween2And50(TestCase tc) {
        return tc.draw(integers().map(n -> 2 + Math.floorMod(n, 49)), "k");
    }

    /** Every known count strictly below k is suppressed. */
    @HegelTest
    void smallKnownCountsAreSuppressed(TestCase tc) {
        int k = kBetween2And50(tc);
        int rowCount = tc.draw(integers().map(n -> 1 + Math.floorMod(n, k - 1)), "rowCount"); // [1, k-1]

        KAnonymityFilter filter = new KAnonymityFilter(k, "null");

        assertTrue(filter.shouldSuppress(rowCount), "count " + rowCount + " < k=" + k + " must suppress");
    }

    /** A count at or above k is never suppressed (k itself is the inclusive safe boundary). */
    @HegelTest
    void countsAtOrAboveKAreNotSuppressed(TestCase tc) {
        int k = kBetween2And50(tc);
        int over = tc.draw(integers().map(n -> Math.floorMod(n, 1_000_000)), "over"); // [0, 1e6)
        int rowCount = k + over;

        KAnonymityFilter filter = new KAnonymityFilter(k, "null");

        assertFalse(filter.shouldSuppress(rowCount), "count " + rowCount + " >= k=" + k + " must not suppress");
    }

    /** An unknown count (<= 0) is never suppressed — we don't mask what we can't measure. */
    @HegelTest
    void unknownCountsAreNeverSuppressed(TestCase tc) {
        int k = kBetween2And50(tc);
        int rowCount = tc.draw(integers().map(n -> -Math.floorMod(n, 1_000_000)), "rowCount"); // [-1e6, 0]

        KAnonymityFilter filter = new KAnonymityFilter(k, "null");

        assertFalse(filter.shouldSuppress(rowCount), "unknown count " + rowCount + " must not suppress");
    }
}
