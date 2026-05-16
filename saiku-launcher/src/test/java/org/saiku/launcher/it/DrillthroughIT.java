/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drill-through is the single feature users ask Saiku to do most often —
 * pull the raw fact rows behind a measure cell. Both the legacy
 * {@code Query2Resource.execute} (DRILLTHROUGH MDX → SQL) and the new
 * AI-Query async drill-through path go through {@code ThinQueryService}, so
 * regressions are very easy to miss without an end-to-end IT.
 */
public class DrillthroughIT {

    private static SaikuItHarness harness;
    private static final String CUBE = "unknown_foodmart/FoodMart/FoodMart/Sales";
    private static final String AI = "/rest/saiku/api/ai";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void mdxDrillthrough_returnsCellSetWithoutClassCastException() throws Exception {
        // saiku#861 fix: raw DRILLTHROUGH MDX now routes through
        // thinQueryService.drillthrough(ThinQuery) — the type-defaulting
        // guard in Query2Resource.execute lets isMdxDrillthrough() detect
        // the statement even when the JSON body omits the explicit
        // {"type": "MDX"} field.
        String body =
                """
                {
                  "name": "drill-it-1",
                  "cube": {
                    "connection": "unknown_foodmart",
                    "catalog": "FoodMart",
                    "schema": "FoodMart",
                    "name": "Sales",
                    "uniqueName": "[Sales]"
                  },
                  "mdx": "DRILLTHROUGH MAXROWS 5 SELECT FROM [Sales] WHERE ([Measures].[Store Sales], [Product].[Products].[Drink])"
                }
                """;
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertTrue(
                "drillthrough body must NOT carry an error: "
                        + resp.body().substring(0, Math.min(300, resp.body().length())),
                r.path("error").isMissingNode() || r.path("error").isNull());
        // The cellset surface for drillthrough carries the row tuples.
        assertTrue("drillthrough body should include a cellset/runtime field", r.has("cellset") || r.has("runtime"));
    }

    @Test
    public void asyncDrillthrough_resolvesAcrossSessions_saiku862() throws Exception {
        // saiku#862 fix: drillthrough on an async queryId now re-attaches
        // the handle's ThinQuery + CellSet to the current session's
        // ThinQueryService context before invoking drillthrough(name, …).
        // Previously a cross-session call (e.g. Basic auth without a shared
        // cookie jar) returned 404 "Unknown queryId" even though the handle
        // was live.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> submit = harness.postAuthJson(AI + "/query/execute-async", body);
        assertEquals(202, submit.statusCode());
        String queryId = harness.parse(submit).path("queryId").asText();
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> status = harness.getAuth(AI + "/query/status/" + queryId);
            if ("DONE".equals(harness.parse(status).path("status").asText())) break;
            Thread.sleep(100);
        }
        HttpResponse<String> drill = harness.getAuth(AI + "/query/" + queryId + "/drillthrough?maxrows=5");
        assertEquals(
                "drillthrough should succeed after re-attachment, got " + drill.statusCode() + " body=" + drill.body(),
                200,
                drill.statusCode());
    }

    @Test
    public void asyncDrillthroughColumns_resolvesAcrossSessions() throws Exception {
        // Same saiku#862 fix applied to the columns-discovery endpoint.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> submit = harness.postAuthJson(AI + "/query/execute-async", body);
        assertEquals(202, submit.statusCode());
        String queryId = harness.parse(submit).path("queryId").asText();
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> status = harness.getAuth(AI + "/query/status/" + queryId);
            if ("DONE".equals(harness.parse(status).path("status").asText())) break;
            Thread.sleep(100);
        }
        HttpResponse<String> cols = harness.getAuth(AI + "/query/" + queryId + "/drillthrough/columns");
        assertEquals(
                "drillthrough columns should be 200 after re-attachment, got " + cols.statusCode() + " body="
                        + cols.body(),
                200,
                cols.statusCode());
    }

    @Test
    public void drillthroughOnUnknownQueryId_returns4xx() throws Exception {
        HttpResponse<String> resp = harness.getAuth(AI + "/query/no-such-id/drillthrough");
        // Could be 400/404/500 depending on the not-found path; assert it
        // isn't 200 and doesn't 401 (auth works).
        assertTrue("drillthrough on unknown id should not 200, got " + resp.statusCode(), resp.statusCode() >= 400);
        assertNotEquals(401, resp.statusCode());
    }
}
