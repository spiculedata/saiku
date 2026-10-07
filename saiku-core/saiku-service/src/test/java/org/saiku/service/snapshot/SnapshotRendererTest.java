/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Assume;
import org.junit.Test;
import org.saiku.service.snapshot.SnapshotRenderer.Snapshot;

/**
 * The renderers themselves (saiku#1810): both formats encode with no browser, and the renderer's own
 * source contains no networking API — the SSRF guard is structural, not merely validated at runtime.
 */
public class SnapshotRendererTest {

    /**
     * PNG rendering needs at least one font the AWT font system can resolve. CI's ubuntu runner and
     * the shipped Docker image (which installs fontconfig + fonts-dejavu-core) have one; a bare
     * fontless JRE does not. Skip the PNG assertions there rather than reporting a false failure —
     * the Dockerfile is what guarantees the font, so this is a property of the environment.
     */
    /** No-op where the JVM has fonts; skips the test where it genuinely does not. */
    private static void assumeFonts() {
        Assume.assumeTrue("no fonts available to the JVM; skipping PNG assertion", fontsAvailable());
    }

    /** Package-visible so sibling tests can apply the same environment guard. */
    static boolean fontsAvailableForTest() {
        return fontsAvailable();
    }

    private static boolean fontsAvailable() {
        try {
            java.awt.image.BufferedImage probe =
                    new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = probe.createGraphics();
            try {
                g.getFontMetrics(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
                return true;
            } finally {
                g.dispose();
            }
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    private static final Snapshot SNAPSHOT = new Snapshot(
            "Executive Overview",
            List.of(new Snapshot.Row("Total Units", "1,234.5"), new Snapshot.Row("Store Sales", "56,789")));

    @Test
    public void pdfRendererEmitsAWellFormedPdf() {
        byte[] bytes = new PdfSnapshotRenderer().render(SNAPSHOT);
        assertTrue(bytes.length > 100);
        assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.ISO_8859_1));
        assertEquals(SnapshotFormat.PDF, new PdfSnapshotRenderer().format());
    }

    @Test
    public void pngRendererEmitsAWellFormedPng() throws IOException {
        assumeFonts();
        byte[] bytes = new PngSnapshotRenderer().render(SNAPSHOT);
        assertTrue(bytes.length > 100);
        assertEquals(0x89, bytes[0] & 0xff);
        assertEquals('P', bytes[1]);
        assertEquals('N', bytes[2]);
        assertEquals('G', bytes[3]);
        assertEquals(SnapshotFormat.PNG, new PngSnapshotRenderer().format());
    }

    @Test
    public void aNullSnapshotIsRejectedByBothRenderers() {
        assertThrows(SnapshotRenderException.class, () -> new PdfSnapshotRenderer().render(null));
        assertThrows(SnapshotRenderException.class, () -> new PngSnapshotRenderer().render(null));
    }

    @Test
    public void markupInLabelsIsDataNotMarkup() {
        assumeFonts();
        // A label full of angle brackets / entities must not blow up the encoder, and must not be
        // interpreted: both writers take plain strings and escape or rasterise them.
        Snapshot hostile = new Snapshot(
                "<script>alert(1)</script>", List.of(new Snapshot.Row("<b>label</b> & \"quoted\"", "<i>1</i>")));
        assertTrue(new PdfSnapshotRenderer().render(hostile).length > 100);
        assertTrue(new PngSnapshotRenderer().render(hostile).length > 100);
    }

    @Test
    public void anEmptySnapshotStillRenders() {
        assumeFonts(); // the PNG half rasterises text
        assertTrue(new PdfSnapshotRenderer().render(new Snapshot(null, List.of())).length > 100);
        assertTrue(new PngSnapshotRenderer().render(new Snapshot(null, List.of())).length > 100);
    }

    @Test
    public void manyRowsStayBounded() {
        assumeFonts();
        // The PNG canvas is clamped, and both encoders bound their per-cell text; a reference with a
        // lot of panels must not produce an unbounded bitmap.
        List<Snapshot.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            rows.add(new Snapshot.Row("label-" + i, String.valueOf(i)));
        }
        byte[] png = new PngSnapshotRenderer().render(new Snapshot("big", rows));
        assertTrue("PNG must stay within the byte cap the service enforces", png.length < 8 * 1024 * 1024);
    }

    /**
     * The structural half of the SSRF guard: the renderer package must not reference any networking
     * API. If a future change adds {@code java.net}/{@code HttpClient}/{@code URL} here, the render
     * path becomes an SSRF primitive again and this test fails.
     */
    @Test
    public void theRendererPackageReferencesNoNetworkingApi() throws IOException {
        Path pkg = Path.of("src/main/java/org/saiku/service/snapshot");
        assertTrue("snapshot package not found at " + pkg.toAbsolutePath(), Files.isDirectory(pkg));
        try (Stream<Path> files = Files.list(pkg)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                // URLEncoder/URLDecoder in the signer are a text codec, not a fetch; what must not
                // appear is anything that can actually open a connection.
                for (String forbidden : new String[] {
                    "HttpClient", "new URL(", "URLConnection", "HttpURLConnection", "Socket",
                    "openStream", "openConnection", "InetAddress", "RestTemplate", "WebClient"
                }) {
                    assertTrue(f.getFileName() + " must not reference " + forbidden, !src.contains(forbidden));
                }
            }
        }
    }
}
