/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * File-backed admin config + credential storage (saiku#1987). The property under test is the split:
 * a secret must be in {@code secrets.json} and must NOT be in the per-destination settings file.
 */
public class ExportDestinationConfigStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path dir;
    private ExportDestinationConfigStore store;

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("export-destinations").toPath();
        store = new ExportDestinationConfigStore(dir);
    }

    @Test
    public void emptyStoreReportsEmpty() {
        assertTrue(store.get("GOOGLE_DRIVE").isEmpty());
        assertFalse(store.isConfigured("GOOGLE_DRIVE"));
    }

    @Test
    public void roundTripsSettingsAndSecrets() {
        store.save(
                "GOOGLE_DRIVE",
                ExportDestinationConfig.of(
                        Map.of("folderId", "1AbCdEf", "serviceAccountKeyFile", "/etc/saiku/drive.json"),
                        Map.of("privateKey", "-----BEGIN PRIVATE KEY-----abc")));

        ExportDestinationConfig read = store.get("GOOGLE_DRIVE");
        assertEquals("1AbCdEf", read.get("folderId"));
        assertEquals("/etc/saiku/drive.json", read.get("serviceAccountKeyFile"));
        assertEquals("-----BEGIN PRIVATE KEY-----abc", read.secret("privateKey"));
        assertTrue(store.isConfigured("GOOGLE_DRIVE"));
    }

    /** The single most important assertion in this file. */
    @Test
    public void aSecretNeverLandsInThePerDestinationSettingsFile() throws Exception {
        store.save(
                "GOOGLE_DRIVE",
                ExportDestinationConfig.of(Map.of("folderId", "1AbCdEf"), Map.of("privateKey", "TOP-SECRET-KEY")));

        String settings = Files.readString(dir.resolve("GOOGLE_DRIVE.json"), StandardCharsets.UTF_8);
        assertFalse("the secret leaked into the settings file: " + settings, settings.contains("TOP-SECRET-KEY"));
        assertTrue(settings, settings.contains("folderId"));

        String secrets = Files.readString(dir.resolve("secrets.json"), StandardCharsets.UTF_8);
        assertTrue("the secret is not in the credentials file at all: " + secrets, secrets.contains("TOP-SECRET-KEY"));
        // And it is namespaced per destination, so two destinations cannot collide on a field name.
        assertTrue(secrets, secrets.contains("GOOGLE_DRIVE"));
    }

    @Test
    public void savingOneDestinationLeavesAnotherIntact() {
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.of(Map.of("folderId", "one"), Map.of("k", "v1")));
        store.save("S3", ExportDestinationConfig.of(Map.of("bucket", "two"), Map.of("k", "v2")));

        assertEquals("v1", store.get("GOOGLE_DRIVE").secret("k"));
        assertEquals("v2", store.get("S3").secret("k"));
        assertEquals("one", store.get("GOOGLE_DRIVE").get("folderId"));
        assertEquals("two", store.get("S3").get("bucket"));
    }

    @Test
    public void aReSaveReplacesRatherThanMergesSecrets() {
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.of(Map.of("folderId", "one"), Map.of("a", "1", "b", "2")));
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.of(Map.of("folderId", "two"), Map.of("a", "9")));
        ExportDestinationConfig read = store.get("GOOGLE_DRIVE");
        assertEquals("9", read.secret("a"));
        assertNull("a replaced secret must not linger", read.secret("b"));
        assertEquals("two", read.get("folderId"));
    }

    @Test
    public void deleteRemovesSettingsAndCredentialsTogether() throws Exception {
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.of(Map.of("folderId", "one"), Map.of("k", "v")));
        store.delete("GOOGLE_DRIVE");
        assertTrue(store.get("GOOGLE_DRIVE").isEmpty());
        assertFalse(Files.exists(dir.resolve("GOOGLE_DRIVE.json")));
        String secrets = Files.readString(dir.resolve("secrets.json"), StandardCharsets.UTF_8);
        assertFalse("a deleted destination's credential survived: " + secrets, secrets.contains("\"v\""));
    }

    @Test
    public void deleteIsIdempotent() {
        store.delete("GOOGLE_DRIVE");
        store.delete("GOOGLE_DRIVE");
        assertTrue(store.get("GOOGLE_DRIVE").isEmpty());
    }

    /** Ids become file names, so a traversal attempt must never reach the filesystem. */
    @Test
    public void rejectsAnIdThatIsNotUpperSnakeCase() {
        for (String bad : new String[] {"../escape", "a/b", "a b", "1LEADING_DIGIT", ""}) {
            try {
                store.save(bad, ExportDestinationConfig.ofSettings(Map.of("x", "y")));
                fail("expected a rejection of the id '" + bad + "'");
            } catch (IllegalArgumentException expected) {
                // correct
            }
        }
    }

    @Test
    public void aTraversalIdReadsBackAsUnconfiguredRatherThanReachingDisk() throws Exception {
        assertTrue(store.get("../../etc/passwd").isEmpty());
    }

    @Test
    public void lookupIsCaseInsensitive() {
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.ofSettings(Map.of("folderId", "one")));
        assertEquals("one", store.get("google_drive").get("folderId"));
    }

    /** A corrupt config must degrade to "unconfigured" + one WARN, never take down a scheduled run. */
    @Test
    public void aCorruptSettingsFileDegradesToUnconfigured() throws Exception {
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.ofSettings(Map.of("folderId", "one")));
        Files.writeString(dir.resolve("GOOGLE_DRIVE.json"), "{ not json");
        assertTrue(store.get("GOOGLE_DRIVE").isEmpty());
    }

    @Test
    public void aCorruptSecretsFileDegradesToNoCredentials() throws Exception {
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.of(Map.of("folderId", "one"), Map.of("k", "v")));
        Files.writeString(dir.resolve("secrets.json"), "[[[");
        ExportDestinationConfig read = store.get("GOOGLE_DRIVE");
        assertEquals("one", read.get("folderId"));
        assertNull(read.secret("k"));
    }

    @Test
    public void noTemporaryFilesAreLeftBehindAfterASave() throws Exception {
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.of(Map.of("folderId", "one"), Map.of("k", "v")));
        try (var files = Files.list(dir)) {
            assertFalse(
                    "an atomic-write temp file was left behind",
                    files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    public void theSettingsFileIsValidJsonAnOperatorCanEdit() throws Exception {
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.ofSettings(Map.of("folderId", "1AbCdEf")));
        Map<?, ?> parsed = MAPPER.readValue(dir.resolve("GOOGLE_DRIVE.json").toFile(), Map.class);
        assertEquals("1AbCdEf", parsed.get("folderId"));
    }

    @Test
    public void theStoreCreatesItsDirectoryOnFirstSave() throws Exception {
        Path nested = dir.resolve("deeper").resolve("still");
        ExportDestinationConfigStore nestedStore = new ExportDestinationConfigStore(nested);
        nestedStore.save("GOOGLE_DRIVE", ExportDestinationConfig.ofSettings(Map.of("folderId", "one")));
        assertTrue(Files.isReadable(nested.resolve("GOOGLE_DRIVE.json")));
    }

    @Test
    public void forSaikuHomeResolvesTheConventionalDirectory() {
        assertEquals(
                java.nio.file.Paths.get("/var/lib/saiku").resolve("export-destinations"),
                ExportDestinationConfigStore.forSaikuHome("/var/lib/saiku").directory());
    }
}
