/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.svg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * saiku#2527 — the SVG root's intrinsic size must be parsed correctly (root tag
 * only, so the injected community watermark never wins) and the requested raster
 * size must always land within the pixel budget, refused with a typed 4xx when
 * an unsized request cannot.
 */
public class SvgGeometryTest {

    private static final float MAX = 2000f;

    private static SvgGeometry.Intrinsic parse(String svg) {
        return SvgGeometry.parse(svg);
    }

    private static float[] target(String svg, Integer size) {
        return SvgGeometry.target(parse(svg), size, MAX, MAX);
    }

    private static SvgExportException refused(String svg) {
        try {
            target(svg, null);
            fail("expected the oversized request to be refused: " + svg);
        } catch (SvgExportException expected) {
            return expected;
        }
        return null; // unreachable
    }

    // ---- parsing ----

    @Test
    public void parsesWidthHeight() {
        SvgGeometry.Intrinsic in =
                parse("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2600\" height=\"4\"><rect/></svg>");
        assertEquals(2600f, in.width(), 0.001f);
        assertEquals(4f, in.height(), 0.001f);
    }

    @Test
    public void parsesCssUnits() {
        SvgGeometry.Intrinsic in = parse("<svg width=\"2in\" height=\"10pt\"/>");
        assertEquals(192f, in.width(), 0.01f);
        assertEquals(96f / 7.2f, in.height(), 0.01f);
    }

    @Test
    public void fallsBackToViewBox() {
        SvgGeometry.Intrinsic in = parse("<svg viewBox=\"0 0 640 480\"/>");
        assertEquals(640f, in.width(), 0.001f);
        assertEquals(480f, in.height(), 0.001f);
    }

    @Test
    public void percentagesAreUnresolvable() {
        assertNull(parse("<svg width=\"100%\" height=\"100%\"/>"));
        assertNull(parse("<svg width=\"100%\" height=\"100%\" viewBox=\"0 0 40 40\"/>".replace("viewBox", "vi3wBox")));
        SvgGeometry.Intrinsic withViewBox = parse("<svg width=\"100%\" height=\"100%\" viewBox=\"0 0 40 40\"/>");
        assertEquals(40f, withViewBox.width(), 0.001f);
    }

    @Test
    public void missingRootIsUnresolvable() {
        assertNull(parse("not an svg at all"));
        assertNull(parse(""));
        assertNull((Object) null);
        assertNull(SvgGeometry.parse(null));
    }

    @Test
    public void readsRootTagOnlySoTheWatermarkNeverWins() {
        // exportChart injects the community watermark before </svg>; its own
        // svg tag must never be mistaken for the document's root.
        SvgGeometry.Intrinsic in = parse("<svg width=\"800\" height=\"600\"><g>"
                + "<svg width=\"12000\" height=\"12000\" viewBox=\"0 0 10 10\"/></g></svg>");
        assertEquals(800f, in.width(), 0.001f);
        assertEquals(600f, in.height(), 0.001f);
    }

    @Test
    public void quotedGreaterThanInsideAttributesDoesNotTruncateTheRootTag() {
        SvgGeometry.Intrinsic in = parse("<svg width=\"3000\" height=\"10\" data-x='a>b'><rect/></svg>");
        assertEquals(3000f, in.width(), 0.001f);
        assertEquals(10f, in.height(), 0.001f);
    }

    // ---- targeting ----

    @Test
    public void unsizedRequestWithinBudgetIsHonoured() {
        float[] t = target("<svg width=\"800\" height=\"600\"/>", null);
        assertEquals(800f, t[0], 0.001f);
        assertEquals(600f, t[1], 0.001f);
    }

    @Test
    public void unsizedOversizedWidthIsRefusedWithTyped400() {
        SvgExportException e = refused("<svg width=\"3000\" height=\"10\"/>");
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    public void unsizedOversizedHeightIsRefusedWithTyped400() {
        assertEquals(400, refused("<svg width=\"12000\" height=\"12000\"/>").getHttpStatus());
    }

    @Test
    public void sizeHintBoundsTheTallAspectRatio() {
        // The acceptance case: size=32 with a 1x100 SVG returned 32x3200 before.
        float[] t = target("<svg width=\"1\" height=\"100\"/>", 32);
        assertEquals(1f, t[0], 0.001f);
        assertEquals(32f, t[1], 0.001f);
    }

    @Test
    public void sizeHintBoundsAnExtremeAspectRatioAgainstBothSides() {
        // size=2000 on a 10x20 doc scaled the height to 4000 before saiku#2527.
        float[] t = target("<svg width=\"10\" height=\"20\"/>", 2000);
        assertEquals(1000f, t[0], 0.001f);
        assertEquals(2000f, t[1], 0.001f);
    }

    @Test
    public void sizeHintClampsExactlyAsBefore() {
        // The old clamp (32..2000) still holds.
        float[] big = target("<svg width=\"800\" height=\"600\"/>", 99999);
        assertEquals(2000f, big[0], 0.001f);
        assertEquals(1500f, big[1], 0.001f);
        float[] small = target("<svg width=\"800\" height=\"600\"/>", 1);
        assertEquals(32f, small[0], 0.001f);
        assertEquals(24f, small[1], 0.001f);
    }

    @Test
    public void unresolvableDimensionsWithSizeHintStayBounded() {
        float[] t = target("<svg width=\"100%\" height=\"100%\" viewBox=\"0 0 40 40\"/>", 512);
        assertEquals(512f, t[0], 0.001f);
        assertEquals(512f, t[1], 0.001f);
    }

    @Test
    public void unresolvableDimensionsWithoutSizeHintFallBackBounded() {
        float[] t = target("<svg width=\"100%\" height=\"100%\"/>", null);
        assertEquals(800f, t[0], 0.001f);
        assertEquals(600f, t[1], 0.001f);
    }

    @Test
    public void targetToleratesDegenerateIntrinsics() {
        float[] t = target("<svg width=\"0\" height=\"0\" viewBox=\"0 0 0 0\"/>", 512);
        assertEquals(512f, t[0], 0.001f);
        assertEquals(512f, t[1], 0.001f);
    }
}
