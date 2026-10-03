/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import static org.junit.Assert.*;

import java.util.Map;
import org.junit.Test;
import org.saiku.service.export.destination.ExportArtifact;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.saiku.service.export.destination.ExportDestinationConfig;

/**
 * The first-party Drive connector against the SPI (saiku#1987): config validation, the folder
 * containment that the threat model relies on, and the shape of the Drive REST call.
 */
public class GoogleDriveExportDestinationTest {

    /** Records the upload and returns a canned file id — no network, no Google project. */
    private static final class FakeUploadClient implements DriveUploadClient {
        String accessToken;
        String folderId;
        String filename;
        String contentType;
        byte[] content;
        int calls;

        @Override
        public String upload(String token, String folder, String name, String type, byte[] bytes) {
            this.calls++;
            this.accessToken = token;
            this.folderId = folder;
            this.filename = name;
            this.contentType = type;
            this.content = bytes;
            return "file-id-123";
        }

        @Override
        public Map<String, String> fileMetadata(String token, String fileId) {
            return Map.of("id", fileId, "name", filename == null ? "" : filename);
        }
    }

    private static ExportDestinationConfig validConfig() {
        return ExportDestinationConfig.of(
                Map.of(
                        GoogleDriveExportDestination.SETTING_KEY_FILE, "/etc/saiku/drive-key.json",
                        GoogleDriveExportDestination.SETTING_FOLDER_ID, "1AbCdEfGhIjKlMnOp"),
                Map.of());
    }

    // ---------------- registration ----------------

    @Test
    public void publishesAStableIdAndLabel() {
        GoogleDriveExportDestination d = new GoogleDriveExportDestination(new FakeUploadClient());
        assertEquals("GOOGLE_DRIVE", d.id());
        assertEquals("Google Drive", d.displayName());
    }

    @Test
    public void publishesConfigFieldsSoAnAdminUiCanBeRenderedGenerically() {
        GoogleDriveExportDestination d = new GoogleDriveExportDestination(new FakeUploadClient());
        var fields = d.configFields();
        assertTrue(fields.stream().anyMatch(f -> f.name().equals(GoogleDriveExportDestination.SETTING_KEY_FILE)));
        assertTrue(fields.stream().anyMatch(f -> f.name().equals(GoogleDriveExportDestination.SETTING_FOLDER_ID)));
    }

    // ---------------- config validation ----------------

    @Test
    public void acceptsAValidConfig() throws Exception {
        new GoogleDriveExportDestination(new FakeUploadClient()).validateConfig(validConfig());
    }

    @Test
    public void rejectsAnEmptyConfig() {
        try {
            new GoogleDriveExportDestination(new FakeUploadClient())
                    .validateConfig(org.saiku.service.export.destination.ExportDestinationConfig.empty());
            fail("expected a rejection");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no configuration saved"));
        }
    }

    /** A relative path would resolve against the WAR's cwd — silent misdelivery beats a refusal. */
    @Test
    public void rejectsARelativeKeyFilePath() {
        ExportDestinationConfig c = ExportDestinationConfig.ofSettings(Map.of(
                GoogleDriveExportDestination.SETTING_KEY_FILE, "relative/drive.json",
                GoogleDriveExportDestination.SETTING_FOLDER_ID, "1AbCdEf"));
        try {
            new GoogleDriveExportDestination(new FakeUploadClient()).validateConfig(c);
            fail("expected a rejection of the relative path");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("absolute path"));
        }
    }

    @Test
    public void rejectsAMalformedFolderId() {
        for (String bad : new String[] {"", "has space", "quote\"", "brace}", "ab"}) {
            ExportDestinationConfig c = ExportDestinationConfig.ofSettings(Map.of(
                    GoogleDriveExportDestination.SETTING_KEY_FILE,
                    "/etc/saiku/drive-key.json",
                    GoogleDriveExportDestination.SETTING_FOLDER_ID,
                    bad));
            try {
                new GoogleDriveExportDestination(new FakeUploadClient()).validateConfig(c);
                fail("expected a rejection of the folder id '" + bad + "'");
            } catch (ExportDeliveryException expected) {
                // correct — a quote/bracket in the folder id would break the metadata JSON we build
            }
        }
    }

    @Test
    public void namesTheMissingFieldWithoutEchoingAnyValue() {
        ExportDestinationConfig c = ExportDestinationConfig.ofSettings(
                Map.of(GoogleDriveExportDestination.SETTING_KEY_FILE, "/etc/saiku/drive-key.json"));
        try {
            new GoogleDriveExportDestination(new FakeUploadClient()).validateConfig(c);
            fail("expected a rejection of the missing folderId");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(GoogleDriveExportDestination.SETTING_FOLDER_ID));
            assertFalse(e.getMessage(), e.getMessage().contains("/etc/saiku/drive-key.json"));
        }
    }

    // ---------------- delivery ----------------

    @Test
    public void deliversTheArtifactIntoTheConfiguredFolder() throws Exception {
        FakeUploadClient client = new FakeUploadClient();
        GoogleDriveExportDestination d = new GoogleDriveExportDestination(client, key -> () -> "canned-token");
        java.nio.file.Path key = java.nio.file.Files.createTempFile("drive-key", ".json");
        java.nio.file.Files.writeString(key, serviceAccountJson());

        ExportDestinationConfig c = ExportDestinationConfig.of(
                Map.of(
                        GoogleDriveExportDestination.SETTING_KEY_FILE,
                        key.toString(),
                        GoogleDriveExportDestination.SETTING_FOLDER_ID,
                        "1AbCdEfGhIjKlMnOp"),
                Map.of());
        var result = d.deliver(ExportArtifact.of("sales.csv", "text/csv", "a,b\n".getBytes()), c);

        assertEquals(1, client.calls);
        assertEquals("1AbCdEfGhIjKlMnOp", client.folderId);
        assertEquals("canned-token", client.accessToken);
        assertEquals("sales.csv", client.filename);
        assertEquals("text/csv", client.contentType);
        assertEquals("GOOGLE_DRIVE", result.destinationId());
        assertEquals("file-id-123", result.remoteId());
        assertTrue(result.description(), result.description().contains("sales.csv"));
        java.nio.file.Files.deleteIfExists(key);
    }

    /** A key file that was moved or chmod'ed away since the config was saved must fail loudly. */
    @Test
    public void failsClearlyWhenTheKeyFileIsNoLongerReadable() {
        GoogleDriveExportDestination d =
                new GoogleDriveExportDestination(new FakeUploadClient(), key -> () -> "canned-token");
        ExportDestinationConfig c = ExportDestinationConfig.ofSettings(Map.of(
                GoogleDriveExportDestination.SETTING_KEY_FILE, "/definitely/not/here/drive-key.json",
                GoogleDriveExportDestination.SETTING_FOLDER_ID, "1AbCdEfGhIjKlMnOp"));
        try {
            d.deliver(ExportArtifact.of("sales.csv", "text/csv", new byte[] {1}), c);
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not readable"));
        }
    }

    @Test
    public void aMalformedKeyFileIsAConfigErrorNotACrash() throws Exception {
        java.nio.file.Path badKey = java.nio.file.Files.createTempFile("drive-key-bad", ".json");
        java.nio.file.Files.writeString(badKey, "{ this is not json");
        ExportDestinationConfig c = ExportDestinationConfig.ofSettings(Map.of(
                GoogleDriveExportDestination.SETTING_KEY_FILE,
                badKey.toString(),
                GoogleDriveExportDestination.SETTING_FOLDER_ID,
                "1AbCdEfGhIjKlMnOp"));
        try {
            new GoogleDriveExportDestination(new FakeUploadClient(), key -> () -> "canned-token")
                    .deliver(ExportArtifact.of("sales.csv", "text/csv", new byte[] {1}), c);
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not valid JSON"));
        } finally {
            java.nio.file.Files.deleteIfExists(badKey);
        }
    }

    /**
     * A minimal but real service-account key. The RSA key is generated at test time so the file is
     * genuinely parseable — a fake base64 blob would let a broken PEM parser pass.
     */
    static String serviceAccountJson() {
        try {
            var kp = java.security.KeyPairGenerator.getInstance("RSA");
            kp.initialize(2048);
            var pem = "-----BEGIN PRIVATE KEY-----\n"
                    + java.util.Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(kp.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            return "{\"type\":\"service_account\",\"client_email\":\"saiku@example.iam.gserviceaccount.com\","
                    + "\"private_key_id\":\"abc123\","
                    + "\"private_key\":\""
                    + pem.replace("\n", "\\n") + "\"}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
