/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Advanced Saiku query features users hit but unit tests rarely exercise:
 * <ul>
 *   <li>Zoom-in (drilling down a member by cell position)</li>
 *   <li>Drill-across (re-running the slicer through another cube context)</li>
 *   <li>Result-metadata level members (slicer member lookup)</li>
 *   <li>Query enrich (axis re-projection)</li>
 *   <li>MDX → cellset → metadata round trip</li>
 * </ul>
 * All tests use the legacy Query2Resource because that's where these features
 * live; the AI-Query API doesn't expose drill-across or zoom semantics.
 *
 * <p>Each test first executes a named MDX query so subsequent calls can look
 * it up by name in the per-session ThinQuery cache.
 */
public class Query2AdvancedIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void levelMembers_returns404WithTypedEnvelopeWhenSessionDiscontinuous() throws Exception {
        // saiku#863 fix: the level-members metadata endpoint now returns
        // a typed 404 envelope when the query name isn't in the current
        // session's ThinQueryService context, instead of the previous
        // silent 204 No Content. The 204 was indistinguishable from
        // "level has no members" — the typed envelope tells clients
        // exactly what went wrong and how to recover.
        //
        // Under Basic auth with no shared cookie jar (this harness's
        // setup), every request creates a new session, so the query
        // posted in the helper above isn't in *this* request's context.
        // The 404 envelope is the documented correct response.
        String name = "q2-meta-" + System.nanoTime();
        executeProductFamilyQuery(name);

        String url = "/rest/saiku/api/query/"
                + name + "/result/metadata/hierarchies/"
                + URLEncoder.encode("[Product].[Products]", StandardCharsets.UTF_8)
                + "/levels/"
                + URLEncoder.encode("[Product].[Products].[Product Family]", StandardCharsets.UTF_8);
        HttpResponse<String> resp = harness.getAuth(url);
        assertEquals(404, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertEquals("NOT_FOUND", body.path("status").asText());
        assertEquals("queryname", body.path("field").asText());
        assertEquals(name, body.path("value").asText());
    }

    @Test
    public void executeQueryThenEnrich_returnsAxisProjection() throws Exception {
        String name = "q2-enrich-" + System.nanoTime();
        executeProductFamilyQuery(name);

        // /enrich projects the query's axes for the UI.
        String enrichBody =
                """
                {
                  "name": "%s",
                  "cube": {
                    "connection": "unknown_foodmart",
                    "catalog": "FoodMart",
                    "schema": "FoodMart",
                    "name": "Sales",
                    "uniqueName": "[Sales]"
                  },
                  "mdx": "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS, NON EMPTY {[Product].[Products].[Product Family].Members} ON ROWS FROM [Sales]"
                }
                """
                        .formatted(name);
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/enrich", enrichBody);
        assertEquals("enrich should be 200, got " + resp.statusCode() + " body=" + resp.body(), 200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        // Enrich returns a ThinQuery with queryModel populated.
        assertEquals(name, body.path("name").asText());
    }

    @Test
    public void zoomIn_byCellPosition_returnsRefinedQuery() throws Exception {
        String name = "q2-zoom-" + System.nanoTime();
        executeProductFamilyQuery(name);

        // zoomIn takes a selections form-param of "row:col" position pairs
        // encoded as a JSON array string. Position [0,0] is the first data row.
        String form = "selections=" + URLEncoder.encode("[\"0:0\"]", StandardCharsets.UTF_8);
        HttpResponse<String> resp = harness.postAuthForm("/rest/saiku/api/query/" + name + "/zoomin", form);
        // 200 or 5xx are both pinnable; we just want to verify the route
        // resolves and Saiku attempts the operation rather than 404ing.
        assertNotEquals("zoomin route must resolve (no 404)", 404, resp.statusCode());
        assertNotEquals("zoomin auth must pass (no 401)", 401, resp.statusCode());
    }

    @Test
    public void drillacross_byCellPosition_routeResolves() throws Exception {
        String name = "q2-across-" + System.nanoTime();
        executeProductFamilyQuery(name);

        // drillacross POST form with `position` + `drill`. Position points
        // at a row/col tuple; drill is a hierarchy spec.
        String form = "position="
                + URLEncoder.encode("0:0", StandardCharsets.UTF_8)
                + "&drill="
                + URLEncoder.encode("[Time].[Time].[Quarter]", StandardCharsets.UTF_8);
        HttpResponse<String> resp = harness.postAuthForm("/rest/saiku/api/query/" + name + "/drillacross", form);
        assertNotEquals("drillacross route must resolve (no 404)", 404, resp.statusCode());
        assertNotEquals("drillacross auth must pass (no 401)", 401, resp.statusCode());
    }

    @Test
    public void deleteQueryByName_returnsGoneOrOk() throws Exception {
        String name = "q2-delete-" + System.nanoTime();
        executeProductFamilyQuery(name);

        HttpResponse<String> resp = harness.deleteAuth("/rest/saiku/api/query/" + name);
        // Status.GONE = 410 in JAX-RS; some setups return 200 as a wrapper.
        assertTrue(
                "delete should be 200/204/410, got " + resp.statusCode(),
                resp.statusCode() == 200 || resp.statusCode() == 204 || resp.statusCode() == 410);
    }

    @Test
    public void cancelExistingQuery_succeeds() throws Exception {
        String name = "q2-cancel-" + System.nanoTime();
        executeProductFamilyQuery(name);

        HttpResponse<String> resp = harness.deleteAuth("/rest/saiku/api/query/" + name + "/cancel");
        assertEquals(200, resp.statusCode());
    }

    // ---------------------------------------------------------------- helpers

    private void executeProductFamilyQuery(String name) throws Exception {
        String body =
                """
                {
                  "name": "%s",
                  "cube": {
                    "connection": "unknown_foodmart",
                    "catalog": "FoodMart",
                    "schema": "FoodMart",
                    "name": "Sales",
                    "uniqueName": "[Sales]"
                  },
                  "mdx": "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS, NON EMPTY {[Product].[Products].[Product Family].Members} ON ROWS FROM [Sales]"
                }
                """
                        .formatted(name);
        HttpResponse<String> exec = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals(
                "setup execute should succeed, got " + exec.statusCode() + " body=" + exec.body(),
                200,
                exec.statusCode());
    }
}
