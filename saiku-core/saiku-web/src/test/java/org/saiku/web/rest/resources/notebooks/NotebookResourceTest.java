/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.notebooks;

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
 * Unit test for {@link NotebookResource} (issue #1108). Stubs the datasource +
 * session services so we can assert the opaque-JSON CRUD round-trip, listing,
 * deletion and path-safety without standing up a real JCR — mirrors {@code
 * AppResourceTest}. {@link NotebookResource#run} is covered only for its
 * request-shape validation here; exercising it end-to-end needs a live
 * ThinQueryService/OlapDiscoverService, covered by the launcher IT harness.
 */
public class NotebookResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SAMPLE_NOTEBOOK = "{\"id\":\"nb-1\",\"name\":\"Sales notebook\",\"version\":1,"
            + "\"cells\":[{\"id\":\"c1\",\"type\":\"markdown\",\"markdown\":\"# Hello\"},"
            + "{\"id\":\"c2\",\"type\":\"mdx\",\"mdx\":\"SELECT FROM [Sales]\","
            + "\"cube\":{\"connectionName\":\"FoodMart\",\"catalog\":\"FoodMart\",\"schema\":\"FoodMart\",\"cubeName\":\"Sales\"}}]}";

    private StubDatasourceService stubDs;
    private NotebookResource resource;

    @Before
    public void setUp() {
        stubDs = new StubDatasourceService();
        resource = new NotebookResource();
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
        Response saved = resource.save("homes/admin/sales.saikunb", SAMPLE_NOTEBOOK);
        assertEquals(200, saved.getStatus());
        assertEquals("homes/admin/sales.saikunb", stubDs.savedPath);
        assertEquals("admin", stubDs.savedAs);

        Response loaded = resource.load("homes/admin/sales.saikunb");
        assertEquals(200, loaded.getStatus());

        // The document is opaque: what comes back must be semantically identical
        // to what went in — no field interpreted, renamed or dropped by the backend.
        JsonNode original = MAPPER.readTree(SAMPLE_NOTEBOOK);
        JsonNode returned = MAPPER.readTree((String) loaded.getEntity());
        assertEquals(original, returned);
        // Spot-check a cell field survives untouched.
        assertEquals("SELECT FROM [Sales]", returned.at("/cells/1/mdx").asText());
        assertEquals("Sales", returned.at("/cells/1/cube/cubeName").asText());
    }

    @Test
    public void put_updatesExistingNotebook() throws Exception {
        resource.save("homes/admin/sales.saikunb", SAMPLE_NOTEBOOK);
        String updated = "{\"id\":\"nb-1\",\"name\":\"Sales notebook v2\",\"cells\":[]}";
        Response r = resource.update("homes/admin/sales.saikunb", updated);
        assertEquals(200, r.getStatus());
        Response loaded = resource.load("homes/admin/sales.saikunb");
        assertEquals(
                "Sales notebook v2",
                MAPPER.readTree((String) loaded.getEntity()).get("name").asText());
    }

    /* ----------------------------- list ------------------------------ */

    @Test
    public void list_includesSavedNotebook() {
        resource.save("homes/admin/sales.saikunb", SAMPLE_NOTEBOOK);
        Response r = resource.list();
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<IRepositoryObject> files = (List<IRepositoryObject>) r.getEntity();
        assertTrue("list must include the saved .saikunb", files.stream().anyMatch(f -> "homes/admin/sales.saikunb"
                .equals(f.getName())));
        // The listing must be scoped to the .saikunb extension only.
        assertEquals(List.of(".saikunb"), stubDs.lastListType);
    }

    @Test
    public void list_flattensRepositoryTreeToSaikunbFilesOnly() {
        // getFiles returns the repository TREE — top-level folders with matching
        // files nested inside; the resource must flatten that to just the
        // .saikunb file nodes (mirrors AppResource's saiku#1636 fix).
        RepositoryFileObject nested = new RepositoryFileObject(
                "sales.saikunb", "#homes/admin/sales.saikunb", "saikunb", "homes/admin/sales.saikunb", List.of());
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
        List<IRepositoryObject> notebooks = (List<IRepositoryObject>) r.getEntity();
        assertEquals(1, notebooks.size());
        assertEquals("sales.saikunb", notebooks.get(0).getName());
        assertEquals(IRepositoryObject.Type.FILE, notebooks.get(0).getType());
    }

    /* ---------------------------- delete ----------------------------- */

    @Test
    public void delete_removesNotebookFromStoreAndList() {
        stubDs.stored.put("homes/admin/sales.saikunb", SAMPLE_NOTEBOOK);
        Response r = resource.delete("homes/admin/sales.saikunb");
        assertEquals(200, r.getStatus());
        assertFalse(stubDs.stored.containsKey("homes/admin/sales.saikunb"));

        Response loaded = resource.load("homes/admin/sales.saikunb");
        assertEquals(404, loaded.getStatus());
    }

    @Test
    public void delete_missingReturns404() {
        Response r = resource.delete("homes/admin/nope.saikunb");
        assertEquals(404, r.getStatus());
    }

    /* --------------------------- validation -------------------------- */

    @Test
    public void save_nullBodyReturns400() {
        Response r = resource.save("homes/admin/x.saikunb", null);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("VALIDATION_ERROR", body.get("status"));
        assertEquals("body", body.get("field"));
    }

    @Test
    public void save_invalidJsonReturns400() {
        Response r = resource.save("homes/admin/x.saikunb", "{not json");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void save_nonObjectBodyReturns400() {
        Response r = resource.save("homes/admin/x.saikunb", "[1,2,3]");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void save_missingCellsArrayReturns400() {
        Response r = resource.save("homes/admin/x.saikunb", "{\"id\":\"nb-1\",\"name\":\"No cells\"}");
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("body", body.get("field"));
    }

    @Test
    public void load_missingReturns404() {
        Response r = resource.load("homes/admin/nonexistent.saikunb");
        assertEquals(404, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("NOT_FOUND", body.get("status"));
    }

    /* -------------------------- path safety -------------------------- */

    @Test
    public void save_rejectsPathTraversal() {
        Response r = resource.save("../../etc/passwd.saikunb", SAMPLE_NOTEBOOK);
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
        Response r = resource.load("homes/admin/../secret.saikunb");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void delete_rejectsPathTraversal() {
        Response r = resource.delete("homes/admin/..%2f..%2fsecret.saikunb");
        // A decoded '..' segment or a raw one must both be rejected.
        assertEquals(400, r.getStatus());
    }

    /* ------------------------ run() validation ------------------------ */

    @Test
    public void run_nullBodyReturns400() {
        Response r = resource.run(null);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("mdx", body.get("field"));
    }

    @Test
    public void run_blankMdxReturns400() {
        NotebookResource.RunRequest req = new NotebookResource.RunRequest();
        req.mdx = "   ";
        req.cube = new org.saiku.service.olap.ai.AiCubeRef("FoodMart", "FoodMart", "FoodMart", "Sales");
        Response r = resource.run(req);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("mdx", body.get("field"));
    }

    @Test
    public void run_missingCubeReturns400() {
        NotebookResource.RunRequest req = new NotebookResource.RunRequest();
        req.mdx = "SELECT FROM [Sales]";
        Response r = resource.run(req);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("cube", body.get("field"));
    }

    @Test
    public void run_unresolvableCubeReturns400() {
        // No OlapDiscoverService wired -> NotebookCubeResolver.resolve() returns
        // null for any ref, exercising the "unknown cube" branch without needing
        // a live schema.
        NotebookResource.RunRequest req = new NotebookResource.RunRequest();
        req.mdx = "SELECT FROM [Sales]";
        req.cube = new org.saiku.service.olap.ai.AiCubeRef("FoodMart", "FoodMart", "FoodMart", "Sales");
        Response r = resource.run(req);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("cube", body.get("field"));
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
        /** When set, getFiles returns this tree verbatim (to exercise flattening). */
        List<IRepositoryObject> treeOverride;

        @Override
        public String saveFile(String content, String path, String name, List<String> roles) {
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
                    out.add(new RepositoryFileObject(path, "#" + path, "saikunb", path, List.of()));
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
