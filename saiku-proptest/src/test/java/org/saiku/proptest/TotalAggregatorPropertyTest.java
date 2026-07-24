/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.saiku.service.olap.totals.aggregators.MaxAggregator;
import org.saiku.service.olap.totals.aggregators.MinAggregator;
import org.saiku.service.olap.totals.aggregators.SumAggregator;
import org.saiku.service.olap.totals.aggregators.TotalAggregator;

/**
 * Numeric-aggregation invariants for the subtotal aggregators used by the totals machinery.
 *
 * <p>Values are generated as small whole numbers cast to {@code double} (range [-1000, 999]) so sums
 * are exact and order-independence can be asserted without floating-point associativity noise. The
 * aggregators are obtained through the real factory
 * ({@link TotalAggregator#newInstanceByFunctionName}) and cast to their concrete type to reach the
 * {@code addData(double)} / {@code getValue()} surface. {@code AvgAggregator} is deliberately not
 * covered — its {@code addData(double)} is a no-op that needs a live {@code Cell}.
 */
class TotalAggregatorPropertyTest {

    /** Small exact-as-double whole numbers so sums never lose precision. */
    private static final Generator<Double> VALUE = integers().map(n -> (double) (Math.floorMod(n, 2000) - 1000));

    private static final Generator<List<Double>> VALUES = lists(VALUE);

    private static SumAggregator sum() {
        return (SumAggregator) TotalAggregator.newInstanceByFunctionName("sum");
    }

    private static MinAggregator min() {
        return (MinAggregator) TotalAggregator.newInstanceByFunctionName("min");
    }

    private static MaxAggregator max() {
        return (MaxAggregator) TotalAggregator.newInstanceByFunctionName("max");
    }

    private static double sumOf(List<Double> xs) {
        SumAggregator agg = sum();
        for (double x : xs) {
            agg.addData(x);
        }
        return agg.getValue();
    }

    /** SUM is order-independent: feeding a list and its reverse yields the same total. */
    @HegelTest
    void sumIsOrderIndependent(TestCase tc) {
        List<Double> xs = tc.draw(VALUES, "xs");
        List<Double> reversed = new ArrayList<>(xs);
        Collections.reverse(reversed);

        assertEquals(sumOf(xs), sumOf(reversed), "sum must not depend on feed order");
    }

    /** SUM identity: an empty feed is 0.0, and appending a 0.0 never changes the running total. */
    @HegelTest
    void sumIdentityIsZero(TestCase tc) {
        assertEquals(0.0, sumOf(List.of()), "empty sum must be 0.0");

        List<Double> xs = tc.draw(VALUES, "xs");
        List<Double> withZero = new ArrayList<>(xs);
        withZero.add(0.0);

        assertEquals(sumOf(xs), sumOf(withZero), "adding 0.0 must not change the sum");
    }

    /** MIN over a non-empty feed equals {@code Collections.min} and bounds every element from below. */
    @HegelTest
    void minMatchesCollectionsMin(TestCase tc) {
        List<Double> xs = tc.draw(VALUES, "xs");
        tc.assume(!xs.isEmpty());

        MinAggregator agg = min();
        for (double x : xs) {
            agg.addData(x);
        }
        double result = agg.getValue();

        assertEquals(Collections.min(xs), result, "min must equal Collections.min");
        for (double x : xs) {
            assertTrue(result <= x, "min must be <= every element");
        }
    }

    /** MAX over a non-empty feed equals {@code Collections.max} and bounds every element from above. */
    @HegelTest
    void maxMatchesCollectionsMax(TestCase tc) {
        List<Double> xs = tc.draw(VALUES, "xs");
        tc.assume(!xs.isEmpty());

        MaxAggregator agg = max();
        for (double x : xs) {
            agg.addData(x);
        }
        double result = agg.getValue();

        assertEquals(Collections.max(xs), result, "max must equal Collections.max");
        for (double x : xs) {
            assertTrue(result >= x, "max must be >= every element");
        }
    }

    /** MIN and MAX both report null for an empty feed — no data, no bound. */
    @HegelTest
    void minAndMaxAreNullWhenEmpty(TestCase tc) {
        // No draw needed: this is a constant property, but Hegel still runs it once per case.
        assertNull(min().getValue(), "empty min must be null");
        assertNull(max().getValue(), "empty max must be null");
    }
}
