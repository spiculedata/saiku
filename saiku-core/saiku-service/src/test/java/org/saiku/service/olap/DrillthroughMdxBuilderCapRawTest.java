/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License 2.0.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.saiku.olap.util.QueryGuardrails;

/**
 * saiku#1914: {@link DrillthroughMdxBuilder#capRawDrillthrough} — the seam for client-supplied
 * DRILLTHROUGH MDX, which arrives fully assembled (no server-side cap opportunity) via
 * {@code POST /api/query/execute} and {@code /api/query/{name}/drillthrough} with raw MDX.
 *
 * <p>The attack these pin: an authenticated user posts
 * {@code DRILLTHROUGH SELECT ... FROM [Cube]} (no MAXROWS) or inflates the bound to
 * {@code MAXROWS 999999999} and streams an entire fact table into the response — a
 * single-request DoS. The emitted statement must always carry a cap no larger than the
 * server ceiling.
 */
public class DrillthroughMdxBuilderCapRawTest {

    private static final String SELECT = "SELECT {[Measures].[Sales]} ON COLUMNS FROM [Cube]";
    private static final int CEILING = 100_000;

    @Test
    public void bareDrillthroughGetsTheServerCeiling() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT, mdx);
    }

    @Test
    public void inflatedMaxrowsIsClampedDown() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS 999999999 " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT, mdx);
    }

    @Test
    public void smallMaxrowsIsPreserved() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS 5 " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS 5 " + SELECT, mdx);
    }

    @Test
    public void firstRowsetIsRewrittenToABoundedMaxrows() {
        // FIRST_ROWSET is a rowset bound, not a result cap, and Mondrian doesn't parse it
        // anyway (see DrillthroughMdxBuilder's class doc). Collapse it to MAXROWS.
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH FIRST_ROWSET 4 " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS 4 " + SELECT, mdx);
    }

    @Test
    public void oversizedFirstRowsetIsClamped() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH FIRST_ROWSET 500000000 " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT, mdx);
    }

    @Test
    public void unparseableBoundFallsBackToTheCeiling() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS banana " + SELECT, 0);
        assertTrue(
                "must not pass the bogus bound through: " + mdx,
                mdx.startsWith("DRILLTHROUGH MAXROWS " + CEILING + " "));
    }

    @Test
    public void keywordIsCaseInsensitive() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("drillthrough maxrows 7 " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS 7 " + SELECT, mdx);
    }

    @Test
    public void callerSuppliedCeilingIsAlsoClamped() {
        // The 3rd-party caller may itself ask for "no cap" (0) — same outcome.
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH " + SELECT, 0);
        assertTrue(mdx.contains("MAXROWS " + QueryGuardrails.maxRows()));
    }

    @Test
    public void returnClauseSurvivesTheRewrite() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough(
                "DRILLTHROUGH MAXROWS 5 " + SELECT + "\r\n RETURN [Customer].[Country]", 0);
        assertTrue("tail must be preserved verbatim: " + mdx, mdx.endsWith("\r\n RETURN [Customer].[Country]"));
    }

    @Test
    public void nonDrillthroughMdxIsUntouched() {
        // Must not invent a drillthrough out of a plain SELECT.
        String plain = "SELECT {[Measures].[Sales]} ON COLUMNS FROM [Cube]";
        assertEquals(plain, DrillthroughMdxBuilder.capRawDrillthrough(plain, 0));
        assertEquals(null, DrillthroughMdxBuilder.capRawDrillthrough(null, 0));
    }

    @Test
    public void noUnboundedDrillthroughSurvives() {
        // The blanket property: for any input, the emitted statement names a bounded MAXROWS.
        String[] inputs = {
            "DRILLTHROUGH " + SELECT,
            "drillthrough " + SELECT,
            "DRILLTHROUGH MAXROWS 0 " + SELECT,
            "DRILLTHROUGH MAXROWS -1 " + SELECT,
            "DRILLTHROUGH MAXROWS 999999999 " + SELECT,
            "DRILLTHROUGH FIRST_ROWSET 999999999 " + SELECT,
        };
        for (String in : inputs) {
            String out = DrillthroughMdxBuilder.capRawDrillthrough(in, 0);
            assertTrue(in + " → " + out, out.startsWith("DRILLTHROUGH MAXROWS "));
            assertFalse(in + " → " + out, out.contains("999999999"));
            long bound = Long.parseLong(out.split("\\s+")[2]);
            assertTrue(in + " → " + out + " bound " + bound, bound >= 1 && bound <= CEILING);
        }
    }
}
