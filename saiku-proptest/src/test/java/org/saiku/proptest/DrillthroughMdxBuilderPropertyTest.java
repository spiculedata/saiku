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
import org.saiku.olap.util.QueryGuardrails;
import org.saiku.service.olap.DrillthroughMdxBuilder;
import org.saiku.service.util.exception.SaikuQueryRejectedException;

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

    /**
     * saiku#1914: with no positive limit, the server ceiling replaces the absent cap — there
     * is no longer a bare {@code DRILLTHROUGH} branch. A client that asks for "everything" (<=0)
     * gets at most {@link QueryGuardrails#DEFAULT_MAX_ROWS} rows.
     */
    @HegelTest
    void noPositiveLimitAppliesServerCeiling(TestCase tc) {
        String select = tc.draw(sampledFrom(SELECTS), "select");
        int maxrows = tc.draw(integers().min(-5).max(0), "maxrows");
        // sampledFrom rejects null elements, so the absent case is drawn as a flag.
        boolean absentFirstRowset = tc.draw(booleans(), "absentFirstRowset");
        Integer firstRowset =
                absentFirstRowset ? null : tc.draw(integers().min(-5).max(0), "firstRowset");
        boolean isMondrian = tc.draw(booleans(), "isMondrian");

        String mdx = DrillthroughMdxBuilder.build(select, maxrows, firstRowset, null, isMondrian);

        int expectedCap = QueryGuardrails.clampMaxRows(maxrows);
        assertEquals("DRILLTHROUGH MAXROWS " + expectedCap + " " + select, mdx);
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

    /**
     * saiku#2420 — THE raw-statement invariant: whatever the clamp is handed, it either emits a
     * statement whose leading cap sits in {@code [1, maxRows()]}, or refuses with the typed
     * rejection. It never hands back a drillthrough carrying an out-of-range or absent bound.
     */
    @HegelTest
    void capRawNeverEmitsAnUnboundedDrillthrough(TestCase tc) {
        String mdx = tc.draw(sampledFrom(RAW_CORPUS), "raw-mdx");
        int requested = tc.draw(integers().min(-5).max(5000), "requested");

        String out;
        try {
            out = DrillthroughMdxBuilder.capRawDrillthrough(mdx, requested);
        } catch (SaikuQueryRejectedException e) {
            tc.note("refused: " + mdx);
            return; // fail-closed rejection is an acceptable outcome
        }
        if (out == mdx) {
            // Returned unchanged is only legitimate for a provably-non-drillthrough,
            // comment-free statement (see the builder's contract).
            assertTrue(
                    !mdx.contains("/*") && !mdx.contains("--") && !mdx.contains("//"),
                    "commented statement must never pass through unchanged: " + mdx);
            return;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^DRILLTHROUGH MAXROWS (\\d+)").matcher(out);
        assertTrue(m.find(), "emitted statement must lead with a MAXROWS bound: " + out);
        long bound = Long.parseLong(m.group(1));
        assertTrue(
                bound >= 1 && bound <= QueryGuardrails.maxRows(),
                "bound " + bound + " outside [1, " + QueryGuardrails.maxRows() + "]: " + out);
        if (requested > 0) {
            assertTrue(
                    bound <= Math.min(requested, QueryGuardrails.maxRows()),
                    "bound " + bound + " exceeds the requested cap " + requested + ": " + out);
        }
    }

    /** The corpus the cap property runs over: every shape saiku#1914/#2327/#2420 shipped. */
    private static final List<String> RAW_CORPUS = List.of(
            "DRILLTHROUGH SELECT FROM [Sales]",
            "DRILLTHROUGH MAXROWS 5 SELECT FROM [Sales]",
            "DRILLTHROUGH MAXROWS 999999999 SELECT FROM [Sales]",
            "DRILLTHROUGH MAXROWS 0 SELECT FROM [Sales]",
            "DRILLTHROUGH MAXROWS -1 SELECT FROM [Sales]",
            "DRILLTHROUGH MAXROWS banana SELECT FROM [Sales]",
            "DRILLTHROUGH FIRST_ROWSET 4 SELECT FROM [Sales]",
            "DRILLTHROUGH FIRST_ROWSET 500000000 SELECT FROM [Sales]",
            "/*a*/DRILLTHROUGH MAXROWS 999999999 SELECT FROM [Sales]",
            "--x\nDRILLTHROUGH MAXROWS 3 SELECT FROM [Sales]",
            "//y\r\n DRILLTHROUGH SELECT FROM [Sales]",
            "DRILLTHROUGH /*c*/ MAXROWS 7 SELECT FROM [Sales]",
            "DRILLTHROUGH MAXROWS /*c*/ 7 SELECT FROM [Sales]",
            "DRILLTHROUGH MAXROWS /*c*/ SELECT FROM [Sales]",
            "/* never closed DRILLTHROUGH SELECT FROM [Sales]",
            "-- never ends DRILLTHROUGH SELECT FROM [Sales]",
            "DRILLTHROUGH",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS FROM [Sales]",
            "/*c*/ SELECT FROM [Sales]");
}
