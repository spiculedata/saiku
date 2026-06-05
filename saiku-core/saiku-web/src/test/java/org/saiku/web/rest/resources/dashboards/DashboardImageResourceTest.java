/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Unit tests for the security-critical pure helpers of {@link DashboardImageResource}:
 * the magic-byte image sniffer (allowlist + SVG/active-content block), the
 * extension→content-type map, and the asset-path derivation.
 */
public class DashboardImageResourceTest {

    private static byte[] bytes(int... ints) {
        byte[] b = new byte[ints.length];
        for (int i = 0; i < ints.length; i++) b[i] = (byte) ints[i];
        return b;
    }

    @Test
    public void sniffsPng() {
        assertEquals(
                "png",
                DashboardImageResource.sniffImageExt(
                        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)));
    }

    @Test
    public void sniffsJpeg() {
        assertEquals("jpg", DashboardImageResource.sniffImageExt(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00)));
    }

    @Test
    public void sniffsGif() {
        assertEquals("gif", DashboardImageResource.sniffImageExt("GIF89a----".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("gif", DashboardImageResource.sniffImageExt("GIF87a----".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void sniffsWebp() {
        assertEquals(
                "webp", DashboardImageResource.sniffImageExt("RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void rejectsSvg() {
        // SVG can carry script — must be rejected (not served same-origin).
        assertNull(DashboardImageResource.sniffImageExt(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8)));
        assertNull(
                DashboardImageResource.sniffImageExt("<?xml version=\"1.0\"?><svg/>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void rejectsArbitraryAndShortInput() {
        assertNull(DashboardImageResource.sniffImageExt("hello world not an image".getBytes(StandardCharsets.UTF_8)));
        assertNull(DashboardImageResource.sniffImageExt(bytes(0x00)));
        assertNull(DashboardImageResource.sniffImageExt(new byte[0]));
        // HTML masquerading as an upload.
        assertNull(DashboardImageResource.sniffImageExt("<html><body>x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void contentTypeMap() {
        assertEquals("image/png", DashboardImageResource.contentTypeFor("png"));
        assertEquals("image/jpeg", DashboardImageResource.contentTypeFor("jpg"));
        assertEquals("image/jpeg", DashboardImageResource.contentTypeFor("jpeg"));
        assertEquals("image/gif", DashboardImageResource.contentTypeFor("gif"));
        assertEquals("image/webp", DashboardImageResource.contentTypeFor("webp"));
        // Anything else → null → the GET endpoint 404s rather than serving it.
        assertNull(DashboardImageResource.contentTypeFor("svg"));
        assertNull(DashboardImageResource.contentTypeFor("html"));
        assertNull(DashboardImageResource.contentTypeFor("saikudash"));
        assertNull(DashboardImageResource.contentTypeFor(""));
    }

    @Test
    public void extensionOf() {
        assertEquals("png", DashboardImageResource.extensionOf("homes/a/t1.png"));
        assertEquals("saikudash", DashboardImageResource.extensionOf("homes/a/x.saikudash"));
        assertEquals("", DashboardImageResource.extensionOf("homes/a/noext"));
    }
}
