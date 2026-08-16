/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.saiku.service.olap.DrillthroughMdxBuilder;

/**
 * Property-based tests for {@link DrillthroughMdxBuilder#build}, which composes the
 * {@code DRILLTHROUGH ...} statement that returns RAW FACT ROWS.
 *
 * <p>Two reasons this deserves properties rather than examples. First, the row cap is a data-exposure
 * control: drillthrough returns unaggregated rows, so emitting a cap HIGHER than the caller asked
 * for hands back more raw data than was authorised. Second, the precedence between
 * {@code firstRowset}, {@code maxrows} and the Mondrian fallback is a four-branch decision whose
 * branches are easy to reorder by accident and hard to notice — the statement still looks plausible.
 *
 * <p>The cap invariant, stated once:
 *
 * <blockquote>
 * whatever row cap appears in the emitted MDX is never greater than the smallest positive limit the
 * caller supplied.
 * </blockquote>
 */
class DrillthroughMdxBuilderPropertyTest {

    private static final List<String> SELECTS = List.of(
            "SELECT FROM [Sales]",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS FROM [Sales]",
            "SELECT {[Measures].[Store Sales]} ON 0, [Time].[Year].Members ON 1 FROM [Sales]");

    /** Extract the integer row cap from the emitted MDX, or -1 when the statement is uncapped. */
    private static long capOf(String mdx) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("DRILLTHROUGH (?:MAXROWS|FIRST_ROWSET) (\\d+) ")
                .matcher(mdx);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** Every statement is a DRILLTHROUGH and carries the caller's select verbatim. */
    @HegelTest
    void alwaysEmitsADrillthroughCarryingTheSelectVerbatim(TestCase tc) {
        String select = tc.draw(sampledFrom(SELECTS), "select");
        int maxrows = tc.draw(integers().min(-5).max(5000), "maxrows");
        Integer firstRowset = tc.draw(sampledFrom(List.of(-1, 0, 1, 10, 999)), "firstRowset");
        boolean isMondrian = tc.draw(booleans(), "isMondrian");

        String mdx = DrillthroughMdxBuilder.build(select, maxrows, firstRowset, null, isMondrian);
        tc.note(mdx);

        assertTrue(mdx.startsWith("DRILLTHROUGH "), "not a DRILLTHROUGH: " + mdx);
        assertTrue(mdx.contains(select), "select was mangled: " + mdx);
    }

    /**
     * THE cap invariant. The emitted cap never exceeds the tightest positive limit requested — a
     * looser cap would return more raw fact rows than the caller authorised.
     */
    @HegelTest
    void theEmittedCapNeverExceedsTheTightestRequestedLimit(TestCase tc) {
        String select = tc.draw(sampledFrom(SELECTS), "select");
        int maxrows = tc.draw(integers().min(-5).max(5000), "maxrows");
        Integer firstRowset = tc.draw(integers().min(-5).max(5000), "firstRowset");
        boolean isMondrian = tc.draw(booleans(), "isMondrian");

        String mdx = DrillthroughMdxBuilder.build(select, maxrows, firstRowset, null, isMondrian);
        long cap = capOf(mdx);
        tc.note("cap=" + cap + " from " + mdx);

        if (cap < 0) {
            // Uncapped is only legitimate when the caller asked for no positive limit at all.
            assertFalse(maxrows > 0 || firstRowset > 0, "dropped a requested row limit: " + mdx);
            return;
        }
        long tightest = Long.MAX_VALUE;
        if (maxrows > 0) {
            tightest = Math.min(tightest, maxrows);
        }
        if (firstRowset > 0) {
            tightest = Math.min(tightest, firstRowset);
        }
        // FIRST_ROWSET on a non-Mondrian backend is a "first N" hint, not a cap on maxrows, so it
        // legitimately ignores maxrows; the cap must still not exceed firstRowset itself.
        if (!isMondrian && firstRowset > 0) {
            assertTrue(cap <= firstRowset, "cap " + cap + " exceeds firstRowset " + firstRowset + ": " + mdx);
        } else {
            assertTrue(cap <= tightest, "cap " + cap + " exceeds the tightest limit " + tightest + ": " + mdx);
        }
    }

    /**
     * Mondrian does not support {@code FIRST_ROWSET}. Emitting it would make the whole drillthrough
     * fail at the engine — so the fallback must hold for every input combination.
     */
    @HegelTest
    void mondrianNeverReceivesFirstRowset(TestCase tc) {
        String select = tc.draw(sampledFrom(SELECTS), "select");
        int maxrows = tc.draw(integers().min(-5).max(5000), "maxrows");
        Integer firstRowset = tc.draw(integers().min(-5).max(5000), "firstRowset");

        String mdx = DrillthroughMdxBuilder.build(select, maxrows, firstRowset, null, true);

        assertFalse(mdx.contains("FIRST_ROWSET"), "FIRST_ROWSET leaked to Mondrian: " + mdx);
    }

    /** On Mondrian with both limits positive, the cap is exactly the smaller of the two. */
    @HegelTest
    void mondrianCapsAtTheSmallerOfBothLimits(TestCase tc) {
        String select = tc.draw(sampledFrom(SELECTS), "select");
        int maxrows = tc.draw(integers().min(1).max(5000), "maxrows");
        Integer firstRowset = tc.draw(integers().min(1).max(5000), "firstRowset");

        String mdx = DrillthroughMdxBuilder.build(select, maxrows, firstRowset, null, true);

        assertEquals(Math.min(maxrows, firstRowset), capOf(mdx), "wrong Mondrian cap: " + mdx);
    }

    /** With no positive limit, the statement is uncapped — and carries neither keyword. */
    @HegelTest
    void noPositiveLimitYieldsABareDrillthrough(TestCase tc) {
        String select = tc.draw(sampledFrom(SELECTS), "select");
        int maxrows = tc.draw(integers().min(-5).max(0), "maxrows");
        // sampledFrom rejects null elements, so the absent case is drawn as a flag.
        boolean absentFirstRowset = tc.draw(booleans(), "absentFirstRowset");
        Integer firstRowset =
                absentFirstRowset ? null : tc.draw(integers().min(-5).max(0), "firstRowset");
        boolean isMondrian = tc.draw(booleans(), "isMondrian");

        String mdx = DrillthroughMdxBuilder.build(select, maxrows, firstRowset, null, isMondrian);

        assertEquals("DRILLTHROUGH " + select, mdx);
    }

    /** RETURN is appended exactly when a non-blank returns clause is supplied. */
    @HegelTest
    void returnClauseAppearsIffNonBlank(TestCase tc) {
        String select = tc.draw(sampledFrom(SELECTS), "select");
        String returns = tc.draw(
                sampledFrom(List.of("", "   ", "\t", "[Store].[Store Name]", "[Product].[Brand Name], [Time].[Year]")),
                "returns");
        boolean isMondrian = tc.draw(booleans(), "isMondrian");

        String mdx = DrillthroughMdxBuilder.build(select, 100, null, returns, isMondrian);

        if (returns.isBlank()) {
            assertFalse(mdx.contains("RETURN"), "emitted an empty RETURN: " + mdx);
        } else {
            assertTrue(mdx.endsWith("\r\n RETURN " + returns), "RETURN clause malformed: " + mdx);
        }
    }

    /** A null returns clause behaves as absent rather than producing "null" in the MDX. */
    @HegelTest
    void nullReturnsIsTreatedAsAbsent(TestCase tc) {
        String select = tc.draw(sampledFrom(SELECTS), "select");
        int maxrows = tc.draw(integers().min(0).max(500), "maxrows");

        String mdx = DrillthroughMdxBuilder.build(select, maxrows, null, null, false);

        assertFalse(mdx.contains("RETURN"), "null returns produced a RETURN: " + mdx);
        assertFalse(mdx.contains("null"), "null leaked into the MDX: " + mdx);
    }

    /** Deterministic: identical inputs always produce identical MDX. */
    @HegelTest
    void buildIsDeterministic(TestCase tc) {
        String select = tc.draw(fromRegex("SELECT FROM \\[[A-Za-z ]{1,12}\\]"), "select");
        int maxrows = tc.draw(integers().min(-5).max(2000), "maxrows");
        Integer firstRowset = tc.draw(integers().min(-5).max(2000), "firstRowset");
        boolean isMondrian = tc.draw(booleans(), "isMondrian");
        String returns = tc.draw(sampledFrom(List.of("", "[Store].[Name]")), "returns");

        assertEquals(
                DrillthroughMdxBuilder.build(select, maxrows, firstRowset, returns, isMondrian),
                DrillthroughMdxBuilder.build(select, maxrows, firstRowset, returns, isMondrian));
    }
}
