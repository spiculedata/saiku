/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.svg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

/**
 * saiku#2527 — the real Batik path: a raster export must never come out larger
 * than the pixel budget, whatever the SVG's own width/height say, and ordinary
 * charts must be unchanged (same size the old single-hint path produced).
 */
public class ConverterTest {

    private static final int MAX = 2000;

    private static byte[] png(String svg, Integer size) throws Exception {
        Converter converter = Converter.byType("PNG");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.convert(new ByteArrayInputStream(svg.getBytes("UTF-8")), out, size);
        return out.toByteArray();
    }

    /** PNG IHDR: width at byte 16, height at 20 (big endian). */
    private static int[] dims(byte[] png) {
        byte[] sig = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        for (int i = 0; i < sig.length; i++) {
            assertEquals("not a PNG", sig[i], png[i]);
        }
        int w = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        int h = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
        return new int[] {w, h};
    }

    private static String chart(int w, int h) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + w + "\" height=\"" + h + "\">"
                + "<rect width=\"1\" height=\"1\" fill=\"red\"/></svg>";
    }

    @Test
    public void ordinaryChartIsUnchanged() throws Exception {
        int[] d = dims(png(chart(800, 600), null));
        assertEquals(800, d[0]);
        assertEquals(600, d[1]);
    }

    @Test
    public void unsizedOversizedSvgIsRefusedBeforeAnyRaster() throws Exception {
        try {
            png(chart(3000, 10), null);
            fail("expected the oversized export to be refused");
        } catch (SvgExportException expected) {
            assertEquals(400, expected.getHttpStatus());
        }
    }

    @Test
    public void sizeHintCannotScalePastTheHeightBudget() throws Exception {
        // saiku#2527 acceptance: size=32 with a 1x100 SVG produced 32x3200 before.
        int[] d = dims(png(chart(1, 100), 32));
        assertTrue("width must stay within the budget: " + d[0], d[0] <= MAX);
        assertTrue("height must stay within the budget: " + d[1], d[1] <= MAX);
        assertEquals(32, d[1]);
    }

    @Test
    public void unsizedPercentDimensionsStayBounded() throws Exception {
        int[] d = dims(png(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" height=\"100%\" "
                        + "viewBox=\"0 0 40 40\"><rect width=\"1\" height=\"1\" fill=\"red\"/></svg>",
                512));
        assertTrue("width must stay within the budget: " + d[0], d[0] <= MAX);
        assertTrue("height must stay within the budget: " + d[1], d[1] <= MAX);
    }

    @Test
    public void tiffExportIsRefusedToo() throws Exception {
        // The pixel budget guards every Batik converter, not only PNG.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Converter.byType("TIFF")
                    .convert(new ByteArrayInputStream(chart(3000, 10).getBytes("UTF-8")), out, null);
            fail("an oversized TIFF must be refused");
        } catch (SvgExportException expected) {
            assertEquals(400, expected.getHttpStatus());
        }
    }
}
