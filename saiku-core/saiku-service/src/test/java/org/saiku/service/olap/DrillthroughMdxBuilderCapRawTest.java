/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License 2.0.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.saiku.olap.util.QueryGuardrails;
import org.saiku.service.util.exception.SaikuQueryRejectedException;

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

    // saiku#2420: the old "fall back to prepending the ceiling" produced
    // "DRILLTHROUGH MAXROWS 100000 MAXROWS banana ..." — a guaranteed Mondrian parse error,
    // i.e. fail-closed as a 500. Every unprovable statement is now a typed rejection.

    @Test
    public void unparseableBoundIsRejected() {
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS banana " + SELECT, 0));
    }

    @Test
    public void zeroBoundMeansUnlimitedToMondrian_andIsRejected() {
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS 0 " + SELECT, 0));
    }

    @Test
    public void negativeBoundIsRejected() {
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS -1 " + SELECT, 0));
    }

    @Test
    public void commentedCapClauseCarryingAValidBound_isCappedNot500() {
        // saiku#2420: a TERMINATED comment inside the MAXROWS clause is unambiguous — the
        // number after it is the bound. The old behaviour prepended a second MAXROWS and
        // guaranteed a parse error (a 500); now the statement is capped and parser-verified.
        assertEquals(
                "DRILLTHROUGH MAXROWS 5 " + SELECT,
                DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS /*c*/ 5 " + SELECT, 0));
        assertEquals(
                "DRILLTHROUGH MAXROWS 5 " + SELECT,
                DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS --c\n5 " + SELECT, 0));
        // An inflated bound hiding behind a comment is still clamped to the ceiling.
        assertEquals(
                "DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT,
                DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS /*c*/ 999999999 " + SELECT, 0));
    }

    @Test
    public void commentedCapClauseWithNoBoundAfter_it_isRejected() {
        // A comment then NO number: there is no bound to clamp, and Mondrian would read the
        // clause as absent (UNLIMITED). Refuse rather than prepend a second MAXROWS (500).
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS /*c*/ " + SELECT, 0));
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS --c\n" + SELECT, 0));
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
        // The blanket property: for any input, the clamp either emits a statement naming a
        // bounded MAXROWS in [1, ceiling] or refuses with the typed rejection — it never
        // hands back a drillthrough it cannot bound.
        String[] inputs = {
            "DRILLTHROUGH " + SELECT,
            "drillthrough " + SELECT,
            "DRILLTHROUGH MAXROWS 0 " + SELECT,
            "DRILLTHROUGH MAXROWS -1 " + SELECT,
            "DRILLTHROUGH MAXROWS 999999999 " + SELECT,
            "DRILLTHROUGH FIRST_ROWSET 999999999 " + SELECT,
            "DRILLTHROUGH MAXROWS banana " + SELECT,
            "DRILLTHROUGH",
            "DRILLTHROUGH /*x*/ MAXROWS 999999999 " + SELECT,
        };
        for (String in : inputs) {
            String out;
            try {
                out = DrillthroughMdxBuilder.capRawDrillthrough(in, 0);
            } catch (SaikuQueryRejectedException e) {
                continue; // fail-closed rejection — acceptable outcome
            }
            assertTrue(in + " → " + out, out.startsWith("DRILLTHROUGH MAXROWS "));
            assertFalse(in + " → " + out, out.contains("999999999"));
            long bound = Long.parseLong(out.split("\\s+")[2]);
            assertTrue(in + " → " + out + " bound " + bound, bound >= 1 && bound <= CEILING);
        }
    }

    // saiku#2327: Mondrian's parser skips comments, so a commented DRILLTHROUGH is detected as one.
    // The cap must skip them too, or the statement runs uncapped.

    @Test
    public void capRaw_leadingBlockComment_stillCapsMaxRows() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("/*a*/DRILLTHROUGH " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT, mdx);
    }

    @Test
    public void capRaw_leadingLineComment_dashDash_and_slashSlash_stillCap() {
        assertEquals(
                "DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT,
                DrillthroughMdxBuilder.capRawDrillthrough("--x\nDRILLTHROUGH " + SELECT, 0));
        assertEquals(
                "DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT,
                DrillthroughMdxBuilder.capRawDrillthrough("//x\r\n  DRILLTHROUGH " + SELECT, 0));
    }

    @Test
    public void capRaw_manyCommentsAndWhitespace_stillCap() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough(
                " /* a */ -- b\n // c\n /* d\n e */ DRILLTHROUGH MAXROWS 999999999 " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT, mdx);
    }

    @Test
    public void capRaw_commentBetweenKeywordAndMaxrows_isClamped() {
        String mdx = DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH /*x*/ MAXROWS 999999999 " + SELECT, 0);
        assertEquals("DRILLTHROUGH MAXROWS " + CEILING + " " + SELECT, mdx);
    }

    // saiku#2420: an unterminated block comment is where Mondrian's lexer and the clamp's
    // string model can disagree — the statement MUST NOT be passed through on that ambiguity.

    @Test
    public void capRaw_unterminatedBlockComment_isFailClosed() {
        String raw = "/* never closed DRILLTHROUGH " + SELECT;
        assertThrows(SaikuQueryRejectedException.class, () -> DrillthroughMdxBuilder.capRawDrillthrough(raw, 0));
    }

    @Test
    public void capRaw_commentsWithNoLocatableKeyword_isFailClosed() {
        // "/* closed */ SELECT" cannot reach a drillthrough under any lexer agreement, but the
        // clamp cannot PROVE that when comments are involved (a lexer that models comments
        // differently could expose statement text from inside one), so it refuses.
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("/*c*/ " + SELECT, 0));
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("--c\n" + SELECT, 0));
    }

    @Test
    public void capRaw_plainSelect_isUnchanged() {
        // A comment-free statement that doesn't start with DRILLTHROUGH is provably not a
        // drillthrough under any lexer agreement — passed through untouched.
        assertEquals(SELECT, DrillthroughMdxBuilder.capRawDrillthrough(SELECT, 0));
    }

    @Test
    public void capRaw_keywordAlone_isRejected() {
        assertThrows(
                SaikuQueryRejectedException.class, () -> DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH", 0));
    }

    @Test
    public void capRaw_boundFusedToIdentifer_isRejected() {
        // "5abc" would lex as a number followed by junk; refuse rather than emit an unparseable
        // statement.
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS 5x " + SELECT, 0));
    }

    @Test
    public void capRaw_lineCommentAtEof_keywordInsideComment_isFailClosed() {
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough("-- DRILLTHROUGH " + SELECT, 0));
    }

    @Test
    public void capRaw_overflowingBound_isRejected() {
        assertThrows(
                SaikuQueryRejectedException.class,
                () -> DrillthroughMdxBuilder.capRawDrillthrough(
                        "DRILLTHROUGH MAXROWS 99999999999999999999 " + SELECT, 0));
    }

    @Test
    public void capRaw_capClauseWithNoStatementAfter_isRejectedByTheParserGate() {
        // "DRILLTHROUGH MAXROWS 5" with no SELECT: the clamp emits a bounded statement, and
        // Mondrian's real-parser gate (the connection overload) is what refuses it — the
        // statement does not re-parse as an executable drillthrough. Covered by the
        // launcher IT (rejected statement cases); the string-only clamp cannot tell.
        assertEquals("DRILLTHROUGH MAXROWS 5", DrillthroughMdxBuilder.capRawDrillthrough("DRILLTHROUGH MAXROWS 5", 0));
    }
}
