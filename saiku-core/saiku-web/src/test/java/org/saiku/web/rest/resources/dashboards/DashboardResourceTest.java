/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.web.service.SessionService;

/**
 * Unit test for {@link DashboardResource}. Stubs the datasource + session
 * services so we can assert the JSON round-trip and error paths without
 * standing up a real JCR.
 */
public class DashboardResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StubDatasourceService stubDs;
    private DashboardResource resource;

    @Before
    public void setUp() {
        stubDs = new StubDatasourceService();
        resource = new DashboardResource();
        resource.setDatasourceService(stubDs);
        resource.setSessionService(new StubSessionService("admin", List.of("ROLE_ADMIN")));
    }

    /* ------------------------------ save ------------------------------ */

    @Test
    public void save_serialisesAndForwardsToDatasourceService() throws Exception {
        Dashboard d = sampleDashboard();
        Response r = resource.save("/dashboards/q4.saikudash", MAPPER.writeValueAsString(d));
        assertEquals(200, r.getStatus());
        assertEquals("/dashboards/q4.saikudash", stubDs.savedPath);
        assertEquals("admin", stubDs.savedAs);
        assertNotNull(stubDs.savedContent);
        // The stored content must be parseable as a Dashboard with no field loss.
        Dashboard parsed = readDashboard(stubDs.savedContent);
        assertEquals(d.id, parsed.id);
        assertEquals(d.name, parsed.name);
        assertEquals(2, parsed.layout.tiles.size());
        // The discriminated union must survive — reference + inline both intact.
        assertEquals("reference", parsed.layout.tiles.get(0).query.kind);
        assertEquals("/queries/foo.saiku", parsed.layout.tiles.get(0).query.path);
        assertEquals("inline", parsed.layout.tiles.get(1).query.kind);
        assertNotNull(parsed.layout.tiles.get(1).query.body);
        assertEquals("Sales", parsed.layout.tiles.get(1).query.body.getCube().getCubeName());
    }

    @Test
    public void save_nullBodyReturns400() {
        Response r = resource.save("/x.saikudash", null);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("VALIDATION_ERROR", body.get("status"));
        assertEquals("body", body.get("field"));
    }

    @Test
    public void save_missingLayoutReturns400() {
        Response r = resource.save("/x.saikudash", "{\"id\":\"x\",\"name\":\"X\"}");
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("layout", body.get("field"));
    }

    @Test
    public void save_datasourceServiceFailureReturns500() throws Exception {
        stubDs.failOnSave = true;
        Response r = resource.save("/x.saikudash", MAPPER.writeValueAsString(sampleDashboard()));
        assertEquals(500, r.getStatus());
    }

    @Test
    public void save_invalidJsonReturns400() {
        Response r = resource.save("/x.saikudash", "{not json");
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("body", body.get("field"));
    }

    @Test
    public void save_preservesUnknownTileFields() throws Exception {
        // #1179: the UI sends per-tile fields the Java model doesn't declare
        // (chartOptions, conditionalFormat, cascading, topN). They must survive
        // the save (round-trip into the stored content), not be dropped by
        // re-serialising the typed POJO.
        String json = "{\"id\":\"d1\",\"name\":\"D\",\"version\":1,\"layout\":{\"cols\":12,\"tiles\":["
                + "{\"id\":\"t1\",\"x\":0,\"y\":0,\"w\":6,\"h\":4,\"type\":\"chart\",\"chartType\":\"bar\","
                + "\"chartOptions\":{\"dualAxis\":true,\"trendLine\":\"linear\"},"
                + "\"conditionalFormat\":[{\"column\":\"Sales\",\"type\":\"bar\"}]}]}}";
        Response r = resource.save("/d1.saikudash", json);
        assertEquals(200, r.getStatus());
        JsonNode tile =
                MAPPER.readTree(stubDs.savedContent).get("layout").get("tiles").get(0);
        assertTrue("chartOptions must survive save", tile.has("chartOptions"));
        assertEquals("linear", tile.get("chartOptions").get("trendLine").asText());
        assertTrue("conditionalFormat must survive save", tile.has("conditionalFormat"));
    }

    /* ------------------------------ load ------------------------------ */

    @Test
    public void load_returnsParsedDashboard() throws Exception {
        stubDs.stored.put("/dashboards/q4.saikudash", MAPPER.writeValueAsString(sampleDashboard()));
        Response r = resource.load("/dashboards/q4.saikudash");
        assertEquals(200, r.getStatus());
        Dashboard d = (Dashboard) r.getEntity();
        assertEquals("Q4 Sales", d.name);
        assertEquals(2, d.layout.tiles.size());
    }

    @Test
    public void load_missingReturns404() {
        Response r = resource.load("/nonexistent.saikudash");
        assertEquals(404, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("NOT_FOUND", body.get("status"));
    }

    @Test
    public void load_unparseableReturns500() {
        stubDs.stored.put("/garbage.saikudash", "{not json");
        Response r = resource.load("/garbage.saikudash");
        assertEquals(500, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("ERROR", body.get("status"));
    }

    /* ------------------------------ delete ---------------------------- */

    @Test
    public void delete_removesFile() {
        stubDs.stored.put("/x.saikudash", "{}");
        Response r = resource.delete("/x.saikudash");
        assertEquals(200, r.getStatus());
        assertFalse(stubDs.stored.containsKey("/x.saikudash"));
    }

    @Test
    public void delete_missingReturns404() {
        Response r = resource.delete("/nope.saikudash");
        assertEquals(404, r.getStatus());
    }

    /* ----------------------- round-trip via JSON ---------------------- */

    @Test
    public void roundTrip_jsonStableViaJackson() throws Exception {
        Dashboard original = sampleDashboard();
        String wire = MAPPER.writeValueAsString(original);
        Dashboard back = MAPPER.readValue(wire, Dashboard.class);
        // Re-serialise; second pass must equal the first byte-for-byte to
        // catch any field that's defaulting differently on the second read.
        assertEquals(wire, MAPPER.writeValueAsString(back));

        // Specifically check that NON_NULL drops fields that don't apply
        // to a tile type — a text tile must not carry a 'cube' field on
        // the wire.
        DashboardTile textTile = new DashboardTile();
        textTile.id = "t";
        textTile.type = "text";
        textTile.text = "hello";
        JsonNode tileNode = MAPPER.valueToTree(textTile);
        assertFalse("text tile should not serialise an empty cube field", tileNode.has("cube"));
        assertFalse("text tile should not serialise an empty query field", tileNode.has("query"));
    }

    /* ----------------------------- helpers ---------------------------- */

    private Dashboard sampleDashboard() {
        Dashboard d = new Dashboard();
        d.id = "abc";
        d.name = "Q4 Sales";

        DashboardTile a = new DashboardTile();
        a.id = "tile-a";
        a.x = 0;
        a.y = 0;
        a.w = 6;
        a.h = 4;
        a.type = "chart";
        a.chartType = "bar";
        a.cube = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
        a.query = new TileQuery();
        a.query.kind = "reference";
        a.query.path = "/queries/foo.saiku";
        d.layout.tiles.add(a);

        DashboardTile b = new DashboardTile();
        b.id = "tile-b";
        b.x = 6;
        b.y = 0;
        b.w = 6;
        b.h = 4;
        b.type = "table";
        b.cube = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
        b.query = new TileQuery();
        b.query.kind = "inline";
        b.query.body = new AiQueryRequest();
        b.query.body.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        b.query.body.setMeasures(List.of(new AiMeasureSelection("Store Sales")));
        b.query.body.setRows(List.of(new AiAxisSelection("Product", "Products", "Product Family")));
        d.layout.tiles.add(b);

        return d;
    }

    private Dashboard readDashboard(String json) {
        try {
            return MAPPER.readValue(json, Dashboard.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* ----------------------------- stubs ------------------------------ */

    /** In-memory stand-in for DatasourceService — only the three file
     *  primitives the resource calls are overridden. */
    private static final class StubDatasourceService extends DatasourceService {
        final Map<String, String> stored = new HashMap<>();
        String savedPath;
        String savedAs;
        String savedContent;
        boolean failOnSave;

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
    }

    private static final class StubSessionService extends SessionService {
        private final Map<String, Object> session;

        StubSessionService(String username, List<String> roles) {
            session = new HashMap<>();
            session.put("username", username);
            session.put("roles", roles);
        }

        @Override
        public Map<String, Object> getAllSessionObjects() {
            return session;
        }
    }
}
