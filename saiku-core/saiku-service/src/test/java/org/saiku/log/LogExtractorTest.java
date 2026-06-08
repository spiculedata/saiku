/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.log;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression coverage for the log-fetch flow surfaced on the demo site —
 * see the saiku#869 / demo.saiku.bi pass where the admin "Logs" tab
 * 500'd on every fetch because:
 *
 * <ul>
 *   <li>The legacy log4j 1.x config pointed at {@code ${catalina.base}/logs}
 *       — undefined under the Jetty launcher, so no file ever got written.</li>
 *   <li>{@link LogExtractor#readLog(String)} appended no extension, so the
 *       SPA-side name {@code "saiku"} resolved to {@code <home>/logs/saiku}
 *       (not {@code saiku.log}) and {@code FileUtils.readFileToString}
 *       threw {@code IOException} for the missing path.</li>
 *   <li>{@code AdminResource.getLogFile} translated the IOException to a
 *       generic 500 with text/plain "Could not read log file" — the SPA's
 *       toast just said "Log fetch failed: 500".</li>
 * </ul>
 *
 * The fix appends {@code .log} when missing, returns {@code null} for
 * not-found, and lets the resource render a typed 404.
 */
public class LogExtractorTest {

    private Path tmp;
    private LogExtractor extractor;

    @Before
    public void setUp() throws IOException {
        tmp = Files.createTempDirectory("log-extractor-it-");
        extractor = new LogExtractor();
        extractor.setLogdirectory(tmp.toString());
    }

    @After
    public void tearDown() throws IOException {
        if (tmp != null && Files.exists(tmp)) {
            Files.walk(tmp)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    public void existingLog_returnsContents() throws Exception {
        Files.writeString(tmp.resolve("saiku.log"), "hello world", StandardCharsets.UTF_8);
        assertEquals("hello world", extractor.readLog("saiku"));
    }

    @Test
    public void nameWithDotLogSuffix_acceptedVerbatim() throws Exception {
        Files.writeString(tmp.resolve("saiku.log"), "explicit", StandardCharsets.UTF_8);
        assertEquals("explicit", extractor.readLog("saiku.log"));
    }

    @Test
    public void missingLog_returnsNull() throws Exception {
        // Previously threw IOException → 500 in AdminResource. Must now
        // return null so the resource can emit a clean 404.
        assertNull(extractor.readLog("never-existed"));
    }

    @Test
    public void blankName_returnsNull() throws Exception {
        assertNull(extractor.readLog(""));
        assertNull(extractor.readLog(null));
    }

    @Test(expected = IOException.class)
    public void pathTraversal_doubleDot_rejected() throws Exception {
        extractor.readLog("../etc/passwd");
    }

    @Test(expected = IOException.class)
    public void pathTraversal_forwardSlash_rejected() throws Exception {
        extractor.readLog("subdir/saiku");
    }

    @Test(expected = IOException.class)
    public void pathTraversal_backslash_rejected() throws Exception {
        extractor.readLog("subdir\\saiku");
    }

    @Test
    public void utf8ContentRoundTrips() throws Exception {
        String content = "Saiku ❤️ 東京";
        Files.writeString(tmp.resolve("saiku.log"), content, StandardCharsets.UTF_8);
        assertEquals(content, extractor.readLog("saiku"));
    }

    @Test
    public void nullLogdirectory_handledGracefully() throws Exception {
        // A fresh LogExtractor before Spring wires logdirectory must not NPE.
        LogExtractor fresh = new LogExtractor();
        assertNull(fresh.readLog("anything"));
    }
}
