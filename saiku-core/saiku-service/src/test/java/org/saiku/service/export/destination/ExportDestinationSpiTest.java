/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** Config-shape, redaction and filename-safety tests for the export destination SPI (saiku#1987). */
public class ExportDestinationSpiTest {

    // ---------------- ExportDestinationConfig ----------------

    @Test
    public void configSeparatesSettingsFromSecrets() {
        ExportDestinationConfig c =
                ExportDestinationConfig.of(Map.of("folderId", "1AbCdEf"), Map.of("refreshToken", "ya29.super-secret"));
        assertEquals("1AbCdEf", c.get("folderId"));
        assertNull("a secret must not be readable as a plain setting", c.get("refreshToken"));
        assertEquals("ya29.super-secret", c.secret("refreshToken"));
        assertEquals(java.util.Set.of("refreshToken"), c.secretNames());
    }

    @Test
    public void blankAndNullValuesAreTreatedAsUnset() {
        Map<String, String> raw = new HashMap<>();
        raw.put("folderId", "   ");
        raw.put("other", null);
        ExportDestinationConfig c = ExportDestinationConfig.ofSettings(raw);
        assertTrue(c.isEmpty());
        assertNull(c.get("folderId"));
    }

    @Test
    public void valuesAreTrimmed() {
        ExportDestinationConfig c = ExportDestinationConfig.ofSettings(Map.of("folderId", "  1AbCd  "));
        assertEquals("1AbCd", c.get("folderId"));
    }

    /** The load-bearing one: a secret must never appear in a rendering, a log line or an error. */
    @Test
    public void everyRenderingRedactsSecrets() {
        ExportDestinationConfig c =
                ExportDestinationConfig.of(Map.of("folderId", "1AbCdEf"), Map.of("refreshToken", "ya29.super-secret"));
        String rendered = c.toString();
        assertFalse("toString leaked a secret: " + rendered, rendered.contains("ya29.super-secret"));
        assertTrue(rendered, rendered.contains("refreshToken=***"));
        assertTrue(rendered, rendered.contains("folderId=1AbCdEf"));
    }

    @Test
    public void requireNamesTheFieldButNeverItsValue() {
        ExportDestinationConfig c = ExportDestinationConfig.ofSettings(Map.of("folderId", "s3cr3t-value"));
        try {
            c.require("token");
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("token"));
            assertFalse(e.getMessage(), e.getMessage().contains("s3cr3t-value"));
        }
    }

    @Test
    public void requireSecretNamesTheFieldButNeverItsValue() {
        ExportDestinationConfig c = ExportDestinationConfig.empty();
        try {
            c.requireSecret("privateKey");
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("privateKey"));
        }
    }

    // ---------------- ExportArtifact ----------------

    @Test
    public void artifactDefensivelyCopiesContent() {
        byte[] bytes = {1, 2, 3};
        ExportArtifact a = ExportArtifact.of("a.csv", "text/csv", bytes);
        bytes[0] = 9;
        assertEquals(1, a.content()[0]);
        a.content()[0] = 7;
        assertEquals(1, a.content()[0]);
    }

    @Test
    public void artifactRejectsPathTraversalNames() {
        for (String bad : new String[] {"../escape.csv", "sub/dir.csv", "a\\b.csv", "..", ".hidden.csv", "a\nb.csv"}) {
            try {
                ExportArtifact.of(bad, "text/csv", new byte[0]);
                fail("expected a rejection for filename '" + bad + "'");
            } catch (IllegalArgumentException expected) {
                // correct — a producer must never hand a destination a name that can escape a folder
            }
        }
    }

    @Test
    public void artifactKeepsMetadataAndReportsSize() {
        ExportArtifact a = ExportArtifact.builder("sales.csv", "text/csv", new byte[] {1, 2, 3, 4})
                .metadata("jobId", "abc")
                .metadata("ignored", null)
                .build();
        assertEquals(4, a.size());
        assertEquals("abc", a.metadata().get("jobId"));
        assertFalse(a.metadata().containsKey("ignored"));
        assertEquals("ExportArtifact[filename=sales.csv, contentType=text/csv, size=4]", a.toString());
    }

    // ---------------- ExportDestinationConfigField ----------------

    @Test
    public void configFieldFactoriesSetTheRightFlags() {
        assertTrue(ExportDestinationConfigField.required("a", "A").required());
        assertFalse(ExportDestinationConfigField.required("a", "A").secret());
        assertTrue(ExportDestinationConfigField.secret("b", "B").secret());
        assertFalse(ExportDestinationConfigField.optional("c", "C", "help").required());
    }

    @Test(expected = IllegalArgumentException.class)
    public void configFieldNeedsAName() {
        ExportDestinationConfigField.required("  ", "A");
    }
}
