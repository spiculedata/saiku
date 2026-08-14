/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.saiku.olap.query2.filter.ThinFilter;
import org.saiku.olap.query2.filter.ThinFilter.FilterFlavour;
import org.saiku.olap.query2.filter.ThinFilter.FilterOperator;

/**
 * saiku#1717 — Measure-flavour axis filters were silently dropped in
 * {@link Fat#convertFilters}: the {@code case Measure:} arm was an empty stub,
 * so a client asking to constrain by measure value got MORE data back with no
 * error. These tests pin the fixed behaviour: {@link Fat#measurePredicate}
 * composes a strict {@code <measure> <op> <value>} MDX predicate from the
 * typed parts, and malformed input throws instead of silently widening.
 */
public class FatMeasureFilterTest {

    private static ThinFilter measureFilter(FilterOperator op, String... expressions) {
        return new ThinFilter(FilterFlavour.Measure, op, null, Arrays.asList(expressions));
    }

    // ---- operator mapping matrix ----

    @Test
    public void equalsMapsToSingleEquals() {
        assertEquals(
                "[Measures].[Store Sales] = 100.5",
                Fat.measurePredicate(measureFilter(FilterOperator.EQUALS, "[Measures].[Store Sales]", "100.5")));
    }

    @Test
    public void notEqualMapsToAngleBrackets() {
        assertEquals(
                "[Measures].[Unit Sales] <> 0",
                Fat.measurePredicate(measureFilter(FilterOperator.NOTEQUAL, "[Measures].[Unit Sales]", "0")));
    }

    @Test
    public void greaterMapsToGt() {
        assertEquals(
                "[Measures].[Store Sales] > 100000",
                Fat.measurePredicate(measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales]", "100000")));
    }

    @Test
    public void greaterEqualsMapsToGte() {
        assertEquals(
                "[Measures].[Store Sales] >= 100000",
                Fat.measurePredicate(
                        measureFilter(FilterOperator.GREATER_EQUALS, "[Measures].[Store Sales]", "100000")));
    }

    @Test
    public void smallerMapsToLt() {
        assertEquals(
                "[Measures].[Store Cost] < 50",
                Fat.measurePredicate(measureFilter(FilterOperator.SMALLER, "[Measures].[Store Cost]", "50")));
    }

    @Test
    public void smallerEqualsMapsToLte() {
        assertEquals(
                "[Measures].[Store Cost] <= 50",
                Fat.measurePredicate(measureFilter(FilterOperator.SMALLER_EQUALS, "[Measures].[Store Cost]", "50")));
    }

    // ---- measure reference handling ----

    @Test
    public void bracketedUniqueNamePassesThroughVerbatim() {
        assertEquals(
                "[Measures].[Profit] > 0",
                Fat.measurePredicate(measureFilter(FilterOperator.GREATER, "[Measures].[Profit]", "0")));
    }

    @Test
    public void bareNameIsWrappedAsMeasuresMember() {
        assertEquals(
                "[Measures].[Store Sales] > 1000",
                Fat.measurePredicate(measureFilter(FilterOperator.GREATER, "Store Sales", "1000")));
    }

    @Test
    public void bareNameClosingBracketsAreEscaped() {
        // ']' must double to ']]' so a crafted name can't close the member
        // reference early and smuggle raw MDX after it.
        assertEquals(
                "[Measures].[Weird]] Name] > 1",
                Fat.measurePredicate(measureFilter(FilterOperator.GREATER, "Weird] Name", "1")));
    }

    @Test
    public void measureAndValueAreTrimmed() {
        assertEquals(
                "[Measures].[Unit Sales] = 42",
                Fat.measurePredicate(measureFilter(FilterOperator.EQUALS, "  [Measures].[Unit Sales]  ", " 42 ")));
    }

    @Test
    public void negativeAndScientificValuesAreAccepted() {
        assertEquals(
                "[Measures].[Margin] < -12.5",
                Fat.measurePredicate(measureFilter(FilterOperator.SMALLER, "[Measures].[Margin]", "-12.5")));
        assertEquals(
                "[Measures].[Margin] > 1e6",
                Fat.measurePredicate(measureFilter(FilterOperator.GREATER, "[Measures].[Margin]", "1e6")));
    }

    // ---- saiku#1721: bracketed-branch MDX-injection hardening ----

    @Test
    public void bracketedInjectionPayloadIsRejected() {
        // The bracketed branch was emitted verbatim into FILTER(...), so a crafted
        // expressions[0] carrying an operator + a second member smuggled arbitrary MDX.
        assertThrowsIae(
                measureFilter(
                        FilterOperator.GREATER, "[Measures].[Store Sales] > 0 OR [Measures].[Unit Sales]", "200000"),
                "not a valid unique name");
    }

    @Test
    public void bracketedRefWithTrailingMdxIsRejected() {
        assertThrowsIae(
                measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales]) OR (1=1", "0"),
                "not a valid unique name");
        assertThrowsIae(
                measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales] + [Measures].[Store Cost]", "0"),
                "not a valid unique name");
    }

    @Test
    public void legitBracketedUniqueNamesStillPass() {
        // A single-segment and a multi-segment (dotted) unique name both survive the shape check.
        assertEquals(
                "[Measures].[Store Sales] > 200000",
                Fat.measurePredicate(measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales]", "200000")));
        assertEquals("[Profit] < 0", Fat.measurePredicate(measureFilter(FilterOperator.SMALLER, "[Profit]", "0")));
    }

    // ---- rejection paths (previously ALL of these were silent no-ops) ----

    @Test
    public void likeOperatorIsRejected() {
        assertThrowsIae(
                measureFilter(FilterOperator.LIKE, "[Measures].[Store Sales]", "100"), "not a numeric comparison");
    }

    @Test
    public void nullOperatorIsRejected() {
        assertThrowsIae(measureFilter(null, "[Measures].[Store Sales]", "100"), "operator is required");
    }

    @Test
    public void nonNumericValueIsRejected() {
        // The value slot is the obvious MDX-injection vector on this typed
        // surface — anything that doesn't parse as a number must be refused.
        assertThrowsIae(
                measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales]", "100 OR 1=1"), "must be numeric");
        assertThrowsIae(
                measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales]", "[Measures].[Other]"),
                "must be numeric");
    }

    @Test
    public void wrongArityIsRejected() {
        assertThrowsIae(measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales]"), "exactly 2 expressions");
        assertThrowsIae(
                measureFilter(FilterOperator.GREATER, "[Measures].[Store Sales]", "1", "extra"),
                "exactly 2 expressions");
        assertThrowsIae(
                new ThinFilter(FilterFlavour.Measure, FilterOperator.GREATER, null, null), "exactly 2 expressions");
    }

    @Test
    public void emptyMeasureIsRejected() {
        assertThrowsIae(measureFilter(FilterOperator.GREATER, "   ", "100"), "measure name/uniqueName is required");
    }

    private static void assertThrowsIae(ThinFilter filter, String messageFragment) {
        try {
            String predicate = Fat.measurePredicate(filter);
            fail("Expected IllegalArgumentException, got predicate: " + predicate);
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    "message should mention '" + messageFragment + "' but was: " + expected.getMessage(),
                    expected.getMessage().contains(messageFragment));
        }
    }

    /** Keeps the two valid-list shapes (List.of vs Arrays.asList) honest under trim/parse. */
    @Test
    public void listOfExpressionsWorks() {
        List<String> exps = List.of("[Measures].[Store Sales]", "3.14");
        ThinFilter f = new ThinFilter(FilterFlavour.Measure, FilterOperator.EQUALS, null, exps);
        assertEquals("[Measures].[Store Sales] = 3.14", Fat.measurePredicate(f));
    }
}
