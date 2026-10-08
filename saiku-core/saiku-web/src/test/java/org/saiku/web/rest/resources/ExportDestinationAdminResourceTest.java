/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.export.destination.ExportArtifact;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.saiku.service.export.destination.ExportDeliveryResult;
import org.saiku.service.export.destination.ExportDestination;
import org.saiku.service.export.destination.ExportDestinationConfig;
import org.saiku.service.export.destination.ExportDestinationConfigField;
import org.saiku.service.export.destination.ExportDestinationConfigStore;
import org.saiku.service.export.destination.ExportDestinationRegistry;
import org.saiku.service.user.UserService;

/**
 * saiku#1987: the admin surface over export destinations. The load-bearing assertion is
 * {@link #aStoredCredentialIsNeverEchoedBackByAnyRead()} — the API can accept a secret, but there
 * must be no read path that returns one.
 */
public class ExportDestinationAdminResourceTest {

    /** A destination with one secret and one plain field. */
    private static class FakeDrive implements ExportDestination {
        ExportArtifact delivered;
        ExportDestinationConfig deliveredWith;

        @Override
        public String id() {
            return "GOOGLE_DRIVE";
        }

        @Override
        public String displayName() {
            return "Google Drive";
        }

        @Override
        public List<ExportDestinationConfigField> configFields() {
            return List.of(
                    ExportDestinationConfigField.required("folderId", "Drive folder id"),
                    ExportDestinationConfigField.secret("serviceAccountKeyFile", "Service account key file"));
        }

        @Override
        public void validateConfig(ExportDestinationConfig config) throws ExportDeliveryException {
            config.require("folderId");
            config.requireSecret("serviceAccountKeyFile");
        }

        @Override
        public ExportDeliveryResult deliver(ExportArtifact artifact, ExportDestinationConfig config)
                throws ExportDeliveryException {
            this.delivered = artifact;
            this.deliveredWith = config;
            return ExportDeliveryResult.of(id(), "remote-1", "uploaded " + artifact.filename());
        }
    }

    private FakeDrive drive;
    private ExportDestinationConfigStore store;
    private ExportDestinationAdminResource resource;

    @Before
    public void setUp() {
        drive = new FakeDrive();
        store = new ExportDestinationConfigStore(null);
        resource = new ExportDestinationAdminResource();
        resource.setRegistry(new ExportDestinationRegistry().with("GOOGLE_DRIVE", drive));
        resource.setConfigStore(store);
        resource.setUserService(new UserService() {
            @Override
            public boolean isAdmin() {
                return true;
            }
        });
    }

    /** The response body as a string, so a secret-leak assertion can scan the whole rendering. */
    private static String rendered(Object entity) {
        return String.valueOf(entity);
    }

    // ---------------- listing ----------------

    @Test
    public void listsTheRegisteredDestinationWithItsConfigFields() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out =
                (List<Map<String, Object>>) resource.list().getEntity();
        assertEquals(1, out.size());
        assertEquals("GOOGLE_DRIVE", out.get(0).get("id"));
        assertEquals("Google Drive", out.get(0).get("displayName"));
        assertEquals(false, out.get(0).get("configured"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) out.get(0).get("fields");
        assertEquals(2, fields.size());
    }

    @Test
    public void reportsAnUnknownDestinationAs404() {
        assertEquals(404, resource.get("DROPBOX").getStatus());
    }

    @Test
    public void aNonAdminIsRefused() {
        resource.setUserService(new UserService() {
            @Override
            public boolean isAdmin() {
                return false;
            }
        });
        assertEquals(403, resource.list().getStatus());
        assertEquals(403, resource.get("GOOGLE_DRIVE").getStatus());
        assertEquals(403, resource.delete("GOOGLE_DRIVE").getStatus());
        assertEquals(403, resource.test("GOOGLE_DRIVE").getStatus());
    }

    // ---------------- saving ----------------

    @Test
    public void savesSettingsAndSecrets() {
        Response resp = resource.save(
                "GOOGLE_DRIVE",
                "{\"settings\":{\"folderId\":\"1AbCdEf\"},"
                        + "\"secrets\":{\"serviceAccountKeyFile\":\"/etc/saiku/drive.json\"}}");
        assertEquals(200, resp.getStatus());
        assertEquals("1AbCdEf", store.get("GOOGLE_DRIVE").get("folderId"));
        assertEquals("/etc/saiku/drive.json", store.get("GOOGLE_DRIVE").secret("serviceAccountKeyFile"));
    }

    /** Editing a folder id must not require re-pasting a credential the API cannot read back. */
    @Test
    public void aSaveWithoutSecretsLeavesTheStoredCredentialAlone() {
        resource.save(
                "GOOGLE_DRIVE",
                "{\"settings\":{\"folderId\":\"1AbCdEf\"},"
                        + "\"secrets\":{\"serviceAccountKeyFile\":\"/etc/saiku/drive.json\"}}");
        resource.save("GOOGLE_DRIVE", "{\"settings\":{\"folderId\":\"99999999\"}}");
        assertEquals("99999999", store.get("GOOGLE_DRIVE").get("folderId"));
        assertEquals("/etc/saiku/drive.json", store.get("GOOGLE_DRIVE").secret("serviceAccountKeyFile"));
    }

    @Test
    public void anInvalidConfigIsRejectedWithTheFailingFieldNamed() {
        Response resp = resource.save("GOOGLE_DRIVE", "{\"settings\":{},\"secrets\":{}}");
        assertEquals(400, resp.getStatus());
        assertTrue(
                String.valueOf(resp.getEntity()),
                String.valueOf(resp.getEntity()).contains("folderId"));
        assertTrue(
                "nothing should have been persisted", store.get("GOOGLE_DRIVE").isEmpty());
    }

    @Test
    public void aMalformedBodyIsRejected() {
        assertEquals(400, resource.save("GOOGLE_DRIVE", "{ not json").getStatus());
    }

    // ---------------- the load-bearing one ----------------

    @Test
    public void aStoredCredentialIsNeverEchoedBackByAnyRead() {
        resource.save(
                "GOOGLE_DRIVE",
                "{\"settings\":{\"folderId\":\"1AbCdEf\"},"
                        + "\"secrets\":{\"serviceAccountKeyFile\":\"/etc/saiku/DRIVATE-KEY-PATH\"}}");

        String list = rendered(resource.list().getEntity());
        String one = rendered(resource.get("GOOGLE_DRIVE").getEntity());
        assertFalse("the credential leaked through list(): " + list, list.contains("PRIVATE-KEY-PATH"));
        assertFalse("the credential leaked through get(): " + one, one.contains("PRIVATE-KEY-PATH"));
        // The NAME is reported so an admin UI can render "configured ✓"…
        assertTrue(one, one.contains("serviceAccountKeyFile"));
        // …and the plain setting is still readable, because it is not a secret.
        assertTrue(one, one.contains("1AbCdEf"));
    }

    // ---------------- delete ----------------

    @Test
    public void deleteForgetsSettingsAndCredentials() {
        resource.save(
                "GOOGLE_DRIVE",
                "{\"settings\":{\"folderId\":\"1AbCdEf\"},"
                        + "\"secrets\":{\"serviceAccountKeyFile\":\"/etc/saiku/drive.json\"}}");
        assertEquals(200, resource.delete("GOOGLE_DRIVE").getStatus());
        assertTrue(store.get("GOOGLE_DRIVE").isEmpty());
    }

    // ---------------- the test-delivery probe ----------------

    @Test
    public void theTestProbeDeliversAConstantServerGeneratedFile() {
        resource.save(
                "GOOGLE_DRIVE",
                "{\"settings\":{\"folderId\":\"1AbCdEf\"},"
                        + "\"secrets\":{\"serviceAccountKeyFile\":\"/etc/saiku/drive.json\"}}");
        Response resp = resource.test("GOOGLE_DRIVE");
        assertEquals(200, resp.getStatus());
        assertNotNull(drive.delivered);
        assertEquals("saiku-export-destination-check.csv", drive.delivered.filename());
        assertTrue(
                "the probe body must be the server's constant, not anything a caller supplied",
                drive.delivered.contentAsString().contains("saiku export destination"));
        assertEquals("destination-check", drive.delivered.metadata().get("kind"));
    }

    @Test
    public void theTestProbeRefusesWhenTheDestinationIsUnconfigured() {
        assertEquals(400, resource.test("GOOGLE_DRIVE").getStatus());
        assertNull(drive.delivered);
    }

    @Test
    public void theTestProbeReportsAConfiguredButBrokenDestinationAsABadRequestNotA500() {
        resource.save(
                "GOOGLE_DRIVE",
                "{\"settings\":{\"folderId\":\"1AbCdEf\"},"
                        + "\"secrets\":{\"serviceAccountKeyFile\":\"/etc/saiku/drive.json\"}}");
        resource.setRegistry(new ExportDestinationRegistry().with("GOOGLE_DRIVE", new FakeDrive() {
            @Override
            public ExportDeliveryResult deliver(ExportArtifact artifact, ExportDestinationConfig config)
                    throws ExportDeliveryException {
                throw ExportDeliveryException.remote("HTTP 403");
            }
        }));
        Response resp = resource.test("GOOGLE_DRIVE");
        assertEquals(400, resp.getStatus());
        assertTrue(
                String.valueOf(resp.getEntity()),
                String.valueOf(resp.getEntity()).contains("403"));
    }
}
