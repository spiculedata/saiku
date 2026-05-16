/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-unit contract for {@link DrillthroughMdxBuilder#build(String, int, Integer, String, boolean)}.
 *
 * <p>Caps the regression Tom hit live on 2026-05-15: the AI drillthrough endpoint forwarded
 * {@code ?firstRowset=N} into {@link org.saiku.service.olap.ThinQueryService#drillthrough}, which
 * emitted {@code DRILLTHROUGH FIRST_ROWSET N SELECT ...} — a token Mondrian's MDX parser doesn't
 * recognise. The build was failing with a 500 + opaque message. This builder is the new shared
 * MDX-emission seam; the test pins the four shapes plus the Mondrian-fallback policy.
 */
public class DrillthroughMdxBuilderTest {

    private static final String BASE = "SELECT NON EMPTY {[Measures].[Sales]} ON COLUMNS FROM [Cube]";

    @Test
    public void plain_drillthrough_when_no_caps() {
        String mdx = DrillthroughMdxBuilder.build(BASE, 0, null, null, /*isMondrian*/ true);
        assertTrue(mdx.startsWith("DRILLTHROUGH SELECT"));
        assertFalse("no MAXROWS when not asked for", mdx.contains("MAXROWS"));
        assertFalse("no FIRST_ROWSET when not asked for", mdx.contains("FIRST_ROWSET"));
    }

    @Test
    public void maxrows_only() {
        String mdx = DrillthroughMdxBuilder.build(BASE, 5, null, null, true);
        assertTrue(mdx.startsWith("DRILLTHROUGH MAXROWS 5 SELECT"));
    }

    @Test
    public void firstRowset_on_non_mondrian_emits_first_rowset() {
        String mdx = DrillthroughMdxBuilder.build(BASE, 0, 5, null, /*isMondrian*/ false);
        assertTrue(
                "non-Mondrian backends keep FIRST_ROWSET (XMLA/MSAS support it). Got: " + mdx,
                mdx.startsWith("DRILLTHROUGH FIRST_ROWSET 5 SELECT"));
    }

    @Test
    public void firstRowset_on_mondrian_falls_back_to_maxrows() {
        // Mondrian's MDX grammar doesn't define FIRST_ROWSET. Emitting it produced a 500.
        // The builder must transparently fall back to MAXROWS when the connection is Mondrian.
        String mdx = DrillthroughMdxBuilder.build(BASE, 0, 5, null, /*isMondrian*/ true);
        assertEquals("DRILLTHROUGH MAXROWS 5 " + BASE, mdx);
    }

    @Test
    public void firstRowset_takes_precedence_over_maxrows_on_non_mondrian() {
        String mdx = DrillthroughMdxBuilder.build(BASE, 9, 5, null, false);
        assertTrue(mdx, mdx.startsWith("DRILLTHROUGH FIRST_ROWSET 5 SELECT"));
        assertFalse(mdx, mdx.contains("MAXROWS"));
    }

    @Test
    public void firstRowset_on_mondrian_with_maxrows_supplied_uses_firstRowset_value_as_the_cap() {
        // The intent of firstRowset is "cap the rowset". When falling back to MAXROWS on Mondrian
        // we want the firstRowset count to win — that's what the agent actually asked for. The
        // maxrows hint becomes a secondary lower bound; pick the smaller of the two so we cap as
        // tightly as the user requested.
        String mdx = DrillthroughMdxBuilder.build(BASE, 9, 5, null, true);
        assertEquals("DRILLTHROUGH MAXROWS 5 " + BASE, mdx);

        String wider = DrillthroughMdxBuilder.build(BASE, 3, 5, null, true);
        assertEquals("DRILLTHROUGH MAXROWS 3 " + BASE, wider);
    }

    @Test
    public void returns_clause_appended_when_supplied() {
        String mdx = DrillthroughMdxBuilder.build(BASE, 5, null, "[Customer].[Country]", true);
        assertTrue(mdx, mdx.endsWith("\r\n RETURN [Customer].[Country]"));
    }

    @Test
    public void returns_clause_blank_string_is_ignored() {
        String mdx = DrillthroughMdxBuilder.build(BASE, 5, null, "   ", true);
        assertFalse(mdx.contains("RETURN"));
    }

    @Test
    public void zero_or_negative_firstRowset_does_not_emit_first_rowset() {
        assertFalse(DrillthroughMdxBuilder.build(BASE, 5, 0, null, false).contains("FIRST_ROWSET"));
        assertFalse(DrillthroughMdxBuilder.build(BASE, 5, -1, null, false).contains("FIRST_ROWSET"));
    }
}
