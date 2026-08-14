/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.apps;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryFileObject;
import org.saiku.repository.RepositoryFolderObject;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.web.service.SessionService;

/**
 * Unit test for {@link AppResource}. Stubs the datasource + session services so
 * we can assert the opaque-JSON round-trip, listing, deletion and path-safety
 * without standing up a real JCR. Mirrors {@code DashboardResourceTest}.
 */
public class AppResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SAMPLE_APP = "{\"id\":\"app-1\",\"name\":\"Sales App\",\"version\":1,"
            + "\"pages\":[{\"id\":\"p1\",\"title\":\"Overview\","
            // arbitrary UI-owned nested fields the backend must NOT interpret or drop
            + "\"widgets\":[{\"kind\":\"chart\",\"opts\":{\"stacked\":true,\"palette\":[\"#111\",\"#222\"]}}]}],"
            + "\"theme\":{\"mode\":\"dark\",\"accent\":\"emerald\"}}";

    private StubDatasourceService stubDs;
    private AppResource resource;

    @Before
    public void setUp() {
        stubDs = new StubDatasourceService();
        resource = new AppResource();
        resource.setDatasourceService(stubDs);
        resource.setSessionService(new StubSessionService("admin", List.of("ROLE_ADMIN")));
    }

    @After
    public void tearDown() {
        // saiku#1752: don't bleed the seeded SecurityContext authorities into the next test.
        org.saiku.web.rest.resources.RoleTestSupport.clear();
    }

    /* -------------------------- round-trip --------------------------- */

    @Test
    public void save_thenLoad_roundTripsOpaqueJsonUnchanged() throws Exception {
        Response saved = resource.save("/apps/sales.saikuapp", SAMPLE_APP);
        assertEquals(200, saved.getStatus());
        assertEquals("/apps/sales.saikuapp", stubDs.savedPath);
        assertEquals("admin", stubDs.savedAs);

        Response loaded = resource.load("/apps/sales.saikuapp");
        assertEquals(200, loaded.getStatus());

        // The document is opaque: what comes back must be semantically identical
        // to what went in — no field interpreted, renamed or dropped by the backend.
        JsonNode original = MAPPER.readTree(SAMPLE_APP);
        JsonNode returned = MAPPER.readTree((String) loaded.getEntity());
        assertEquals(original, returned);
        // Spot-check a deeply-nested UI-owned field survives untouched.
        assertTrue(returned.at("/pages/0/widgets/0/opts/stacked").asBoolean());
        assertEquals("emerald", returned.at("/theme/accent").asText());
    }

    @Test
    public void put_updatesExistingApp() throws Exception {
        resource.save("/apps/sales.saikuapp", SAMPLE_APP);
        String updated = "{\"id\":\"app-1\",\"name\":\"Sales App v2\",\"pages\":[]}";
        Response r = resource.update("/apps/sales.saikuapp", updated);
        assertEquals(200, r.getStatus());
        Response loaded = resource.load("/apps/sales.saikuapp");
        assertEquals(
                "Sales App v2",
                MAPPER.readTree((String) loaded.getEntity()).get("name").asText());
    }

    /* ----------------------------- list ------------------------------ */

    @Test
    public void list_includesSavedApp() throws Exception {
        resource.save("/apps/sales.saikuapp", SAMPLE_APP);
        Response r = resource.list();
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<IRepositoryObject> files = (List<IRepositoryObject>) r.getEntity();
        assertTrue("list must include the saved .saikuapp", files.stream().anyMatch(f -> "/apps/sales.saikuapp"
                .equals(f.getName())));
        // The listing must be scoped to the .saikuapp extension only.
        assertEquals(List.of(".saikuapp"), stubDs.lastListType);
    }

    @Test
    public void list_flattensRepositoryTreeToSaikuappFilesOnly() {
        // getFiles returns the repository TREE — top-level folders with matching
        // files nested inside. saiku#1636: the resource must flatten that to just
        // the .saikuapp file nodes, not leak folders (which hid the seeded example
        // app and filled the Apps catalogue with repository folder names).
        RepositoryFileObject nested = new RepositoryFileObject(
                "foodmart-ops.saikuapp",
                "#homes/admin/foodmart-ops.saikuapp",
                "saikuapp",
                "homes/admin/foodmart-ops.saikuapp",
                List.of());
        RepositoryFolderObject admin =
                new RepositoryFolderObject("admin", "#homes/admin", "homes/admin", List.of(), List.of(nested));
        RepositoryFolderObject homes =
                new RepositoryFolderObject("homes", "#homes", "homes", List.of(), List.of(admin));
        RepositoryFolderObject dashboards =
                new RepositoryFolderObject("dashboards", "#dashboards", "dashboards", List.of(), new ArrayList<>());
        stubDs.treeOverride = List.of(dashboards, homes);

        Response r = resource.list();
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<IRepositoryObject> apps = (List<IRepositoryObject>) r.getEntity();
        // Exactly the one nested .saikuapp — no folders.
        assertEquals(1, apps.size());
        assertEquals("foodmart-ops.saikuapp", apps.get(0).getName());
        assertEquals(IRepositoryObject.Type.FILE, apps.get(0).getType());
    }

    /* ---------------------------- delete ----------------------------- */

    @Test
    public void delete_removesAppFromStoreAndList() {
        stubDs.stored.put("/apps/sales.saikuapp", SAMPLE_APP);
        Response r = resource.delete("/apps/sales.saikuapp");
        assertEquals(200, r.getStatus());
        assertFalse(stubDs.stored.containsKey("/apps/sales.saikuapp"));

        Response loaded = resource.load("/apps/sales.saikuapp");
        assertEquals(404, loaded.getStatus());
    }

    @Test
    public void delete_missingReturns404() {
        Response r = resource.delete("/apps/nope.saikuapp");
        assertEquals(404, r.getStatus());
    }

    /* --------------------------- validation -------------------------- */

    @Test
    public void save_nullBodyReturns400() {
        Response r = resource.save("/apps/x.saikuapp", null);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("VALIDATION_ERROR", body.get("status"));
        assertEquals("body", body.get("field"));
    }

    @Test
    public void save_invalidJsonReturns400() {
        Response r = resource.save("/apps/x.saikuapp", "{not json");
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("body", body.get("field"));
    }

    @Test
    public void save_nonObjectBodyReturns400() {
        Response r = resource.save("/apps/x.saikuapp", "[1,2,3]");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void load_missingReturns404() {
        Response r = resource.load("/apps/nonexistent.saikuapp");
        assertEquals(404, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("NOT_FOUND", body.get("status"));
    }

    /* -------------------------- path safety -------------------------- */

    @Test
    public void save_rejectsPathTraversal() {
        Response r = resource.save("/apps/../../etc/passwd.saikuapp", SAMPLE_APP);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("VALIDATION_ERROR", body.get("status"));
        assertEquals("path", body.get("field"));
        // The hostile path must never reach the storage layer.
        assertNull(stubDs.savedPath);
    }

    @Test
    public void load_rejectsPathTraversal() {
        Response r = resource.load("/apps/../secret.saikuapp");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void delete_rejectsPathTraversal() {
        Response r = resource.delete("/apps/..%2f..%2fsecret.saikuapp");
        // A decoded '..' segment or a raw one must both be rejected.
        assertEquals(400, r.getStatus());
    }

    /* ----------------------------- stubs ----------------------------- */

    /** In-memory stand-in for DatasourceService — only the file primitives the
     *  resource calls are overridden. */
    private static final class StubDatasourceService extends DatasourceService {
        final Map<String, String> stored = new HashMap<>();
        String savedPath;
        String savedAs;
        String savedContent;
        List<String> lastListType;
        boolean failOnSave;
        /** When set, getFiles returns this tree verbatim (to exercise flattening). */
        List<IRepositoryObject> treeOverride;

        @Override
        public String saveFile(String content, String path, String name, List<String> roles) {
            if (failOnSave) return "FAILED";
            stored.put(path, content);
            savedPath = path;
            savedAs = name;
            savedContent = content;
            return "Save Okay";
        }

        @Override
        public String removeFile(String path, String name, List<String> roles) {
            if (!stored.containsKey(path)) return "FAILED";
            stored.remove(path);
            return "Remove Okay";
        }

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            return stored.get(path);
        }

        @Override
        public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles) {
            lastListType = type;
            if (treeOverride != null) {
                return treeOverride;
            }
            List<IRepositoryObject> out = new ArrayList<>();
            for (String path : stored.keySet()) {
                boolean matches =
                        type == null || type.isEmpty() || type.stream().anyMatch(path::endsWith);
                if (matches) {
                    out.add(new RepositoryFileObject(path, "#" + path, "saikuapp", path, List.of()));
                }
            }
            return out;
        }
    }

    private static final class StubSessionService extends SessionService {
        private final Map<String, Object> session;

        StubSessionService(String username, List<String> roles) {
            session = new HashMap<>();
            session.put("username", username);
            session.put("roles", roles);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> getAllSessionObjects() {
            // saiku#1752: the resource now reads roles authoritatively from SecurityContextHolder,
            // not this map. Seed the holder from the stubbed session so role-scoped paths still see
            // the roles the test set up.
            org.saiku.web.rest.resources.RoleTestSupport.authenticate(
                    (String) session.get("username"), (List<String>) session.get("roles"));
            return session;
        }
    }
}
