/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.saiku.olap.query2.filter.ThinFilter;
import org.saiku.olap.query2.filter.ThinFilter.FilterFlavour;
import org.saiku.olap.query2.filter.ThinFilter.FilterOperator;
import org.saiku.query.mdx.GenericFilter;
import org.saiku.query.mdx.IFilterFunction;

/**
 * saiku#1721 — CALL-SITE (wiring) coverage for the Measure-flavour filter.
 *
 * <p>The 17 {@link FatMeasureFilterTest} cases all call {@link Fat#measurePredicate} directly, so
 * reverting the {@code case Measure:} arm in {@link Fat#convertFilters} back to a drop-stub (the
 * pre-saiku#1717 bug: {@code qfs.add(...)} removed, filter silently dropped) keeps every one of
 * those 17 green. That is exactly the #1261/#1266/#1271 lesson — a helper unit test does not prove
 * the helper is wired in.
 *
 * <p>This test drives {@link Fat#convertFilters} (the call site) and asserts a Measure
 * {@link ThinFilter} actually produces a {@link GenericFilter} carrying the composed predicate.
 * Delete the {@code qfs.add(...)} at the call site and this test goes red.
 */
public class FatMeasureFilterWiringTest {

    private static ThinFilter measureFilter(FilterOperator op, String... expressions) {
        return new ThinFilter(FilterFlavour.Measure, op, null, Arrays.asList(expressions));
    }

    @Test
    public void measureFilterIsWiredIntoTheFilterSet() {
        // Measure flavour never touches the Query arg, so null is safe here — this isolates the
        // call-site wiring from any live-cube setup.
        List<IFilterFunction> filters = Fat.convertFilters(
                null, List.of(measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales]", "200000")));

        assertFalse("Measure filter must NOT be dropped — the call site must add it", filters.isEmpty());
        assertEquals("exactly one filter function expected for one Measure ThinFilter", 1, filters.size());
        assertTrue("the wired filter must be a GenericFilter", filters.get(0) instanceof GenericFilter);
        GenericFilter gf = (GenericFilter) filters.get(0);
        assertEquals(
                "the GenericFilter must carry the composed measure predicate",
                "[Measures].[Store Sales] > 200000",
                gf.getFilterExpression());
    }

    @Test
    public void bareMeasureNameIsWrappedAndWired() {
        List<IFilterFunction> filters =
                Fat.convertFilters(null, List.of(measureFilter(FilterOperator.SMALLER, "Store Cost", "50")));
        assertEquals(1, filters.size());
        assertEquals("[Measures].[Store Cost] < 50", ((GenericFilter) filters.get(0)).getFilterExpression());
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedMeasureFilterThrowsAtTheCallSite() {
        // A crafted bracketed injection payload must be rejected end-to-end (call site → helper),
        // not just when the helper is exercised in isolation.
        Fat.convertFilters(
                null,
                List.of(measureFilter(
                        FilterOperator.GREATER, "[Measures].[Store Sales] > 0 OR [Measures].[Unit Sales]", "1")));
    }
}
