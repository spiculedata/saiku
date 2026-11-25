/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources.dashboards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.web.service.SessionService;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Supplemental request-path coverage for {@link DashboardImageResource} —
 * upload()/get() walk the auth, size, type-sniffing and storage guards.
 * The pure helpers (magic-byte sniffing, content-type map) are covered by
 * {@link DashboardImageResourceTest}.
 */
public class DashboardImageResourceUploadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** PNG magic bytes — the smallest legal upload. */
    private static final byte[] PNG = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
    /** Magic bytes of an SVG — text, and text is never an allowed image. */
    private static final byte[] SVG = "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes();

    private StubDatasourceService datasources;
    private StubSessionService sessions;
    private DashboardImageResource resource;

    static class StubSessionService extends SessionService {
        private final Map<String, Object> session = new HashMap<>();

        void setUser(String name) {
            session.put("username", name);
        }

        @Override
        public Map<String, Object> getAllSessionObjects() {
            return new HashMap<>(session);
        }
    }

    static class StubDatasourceService extends DatasourceService {
        final Map<String, String> files = new HashMap<>();
        String saveResult = "Save Okay";
        RuntimeException onGet;

        @Override
        public String saveFile(String content, String path, String name, List<String> roles) {
            if (saveResult.startsWith("Err")) {
                throw new RuntimeException(saveResult);
            }
            files.put(path, content);
            return saveResult;
        }

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            if (onGet != null) {
                throw onGet;
            }
            return files.get(path);
        }
    }

    @Before
    public void setUp() {
        datasources = new StubDatasourceService();
        sessions = new StubSessionService();
        sessions.setUser("dev.team01");
        resource = new DashboardImageResource();
        resource.setDatasourceService(datasources);
        resource.setSessionService(sessions);
        // SessionRoles.currentRoles() reads the Spring context.
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("dev.team01", "n/a", "ROLE_ADMIN"));
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static InputStream pngStream() {
        return new ByteArrayInputStream(PNG);
    }

    @Test
    public void uploadWithoutASessionIsUnauthorized() {
        sessions.setUser(null);
        Response r = resource.upload(pngStream(), "tile1");
        assertEquals(401, r.getStatus());
        assertTrue(datasources.files.isEmpty());
    }

    @Test
    public void uploadRejectsPathBearingUsernames() {
        sessions.setUser("../../root");
        Response r = resource.upload(pngStream(), "tile1");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void uploadWithoutAFileIsBadRequest() {
        Response r = resource.upload(null, "tile1");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void uploadRejectsAnInvalidTileId() {
        assertEquals(400, resource.upload(pngStream(), null).getStatus());
        assertEquals(400, resource.upload(pngStream(), "../tile").getStatus());
    }

    @Test
    public void uploadRejectsAnUnsupportedImageType() {
        assertEquals(
                400, resource.upload(new ByteArrayInputStream(SVG), "tile1").getStatus());
    }

    @Test
    public void uploadRejectsAnEmptyBody() {
        assertEquals(
                400,
                resource.upload(new ByteArrayInputStream(new byte[0]), "tile1").getStatus());
    }

    @Test
    public void uploadCapsAtTwoMegabytes() {
        // Slightly over the 2 MiB cap — the read loop must bail out.
        InputStream big = new InputStream() {
            long left = 2 * 1024 * 1024 + 10L;

            @Override
            public int read() {
                if (left-- <= 0) {
                    return -1;
                }
                return 0;
            }
        };
        assertEquals(413, resource.upload(big, "tile1").getStatus());
    }

    @Test
    public void uploadStoresBesideTheDashboardAndReturnsTheDownloadSrc() throws Exception {
        Response r = resource.upload(pngStream(), "tile1");
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body =
                (Map<String, Object>) MAPPER.readValue(MAPPER.writeValueAsBytes(r.getEntity()), Map.class);
        String src = (String) body.get("src");
        assertTrue(src.startsWith("/rest/saiku/api/dashboards/image/get/homes/dev.team01/tile1.png"));
        assertTrue(datasources.files.containsKey("homes/dev.team01/tile1.png"));
    }

    @Test
    public void uploadFailsClosedWhenTheRepositoryRefusesTheSave() {
        datasources.saveResult = "Err disk full";
        assertEquals(403, resource.upload(pngStream(), "tile1").getStatus());
    }

    @Test
    public void uploadSurfacesANonOkaySaveResult() {
        datasources.saveResult = "Over quota";
        Response r = resource.upload(pngStream(), "tile1");
        assertEquals(500, r.getStatus());
        assertTrue(String.valueOf(r.getEntity()).contains("Over quota"));
    }

    @Test
    public void uploadRejectsAResultBlobTheSnifferCannotIdentify() {
        // A byte[] the sniffer reads fine but which maps to no known type.
        assertEquals(
                400,
                resource.upload(new ByteArrayInputStream(new byte[] {0x00, 0x01, 0x02}), "tile1")
                        .getStatus());
    }

    @Test
    public void getWithoutASessionIsUnauthorized() {
        sessions.setUser(null);
        assertEquals(401, resource.get("homes/dev.team01/tile1.png").getStatus());
    }

    @Test
    public void getRefusesUnknownExtensions() {
        assertEquals(404, resource.get("homes/dev.team01/evil.svg").getStatus());
        assertEquals(404, resource.get("homes/dev.team01/noext").getStatus());
    }

    @Test
    public void getReturnsDecodedBytesWithHardenedHeaders() {
        String base64 = java.util.Base64.getEncoder().encodeToString(PNG);
        datasources.files.put("homes/dev.team01/tile1.png", base64);
        Response r = resource.get("homes/dev.team01/tile1.png");
        assertEquals(200, r.getStatus());
        assertEquals("image/png", r.getHeaderString("Content-Type"));
        assertEquals("nosniff", r.getHeaderString("X-Content-Type-Options"));
        assertTrue(((byte[]) r.getEntity()).length > 0);
    }

    @Test
    public void getOfMissingOrCorruptDataIsNotFound() {
        assertEquals(404, resource.get("homes/dev.team01/missing.png").getStatus());
        datasources.files.put("homes/dev.team01/bad.png", "!!!not base64!!!");
        assertEquals(404, resource.get("homes/dev.team01/bad.png").getStatus());
        datasources.onGet = new RuntimeException("repo down");
        assertEquals(404, resource.get("homes/dev.team01/down.png").getStatus());
    }

    @Test
    public void extensionOfLowercasesAndNeedsADot() {
        assertEquals("png", DashboardImageResource.extensionOf("a/b/Tile.PNG"));
        assertEquals("", DashboardImageResource.extensionOf("noext"));
    }

    @Test
    public void contentTypeForMapsOnlyImageTypes() {
        assertEquals("image/png", DashboardImageResource.contentTypeFor("png"));
        assertEquals("image/jpeg", DashboardImageResource.contentTypeFor("jpeg"));
        assertEquals(null, DashboardImageResource.contentTypeFor("svg"));
    }

    @Test
    public void sniffImageExtRecognisesOnlyRealImageMagic() {
        assertEquals("png", DashboardImageResource.sniffImageExt(PNG));
        assertEquals(
                "jpg", DashboardImageResource.sniffImageExt(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01}));
        assertEquals("gif", DashboardImageResource.sniffImageExt("GIF89a".getBytes()));
        assertEquals(null, DashboardImageResource.sniffImageExt(SVG));
        assertEquals(null, DashboardImageResource.sniffImageExt(new byte[0]));
    }

    @Test
    public void aSecondUploadWithTheSameTileIdOverwritesRatherThanDuplicates() throws Exception {
        resource.upload(pngStream(), "tile1");
        assertEquals(1, new ArrayList<>(datasources.files.keySet()).size());
        resource.upload(pngStream(), "tile1");
        assertEquals(1, new ArrayList<>(datasources.files.keySet()).size());
    }
}
