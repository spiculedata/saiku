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
    public void compoundSameDimSlicer_cellShape_drillsThroughWithExactTotals_saiku1714() throws Exception {
        // saiku#1714: two filter selections on the SAME dimension broke
        // drillthrough in the OSBI era (a move-to-columns workaround sat
        // commented in OlapQueryService for a decade). The Mondrian fork now
        // supports compound-slicer DRILLTHROUGH. This pins the exact shape
        // ThinQueryService.drillthrough(cellPosition…) assembles — cell tuple
        // ON COLUMNS, the filter-axis set unparsed into WHERE — and asserts
        // the numbers, because the pre-fix failure mode here is SILENT: rows
        // come back, just the wrong ones.
        String body =
                """
                {
                  "name": "drill-1714-cell",
                  "cube": {
                    "connection": "unknown_foodmart",
                    "catalog": "FoodMart",
                    "schema": "FoodMart",
                    "name": "Sales",
                    "uniqueName": "[Sales]"
                  },
                  "mdx": "DRILLTHROUGH MAXROWS 99999 SELECT ([Measures].[Store Sales], [Product].[Products].[Drink].[Alcoholic Beverages]) ON COLUMNS FROM [Sales] WHERE {[Time].[Time].[1997].[Q1], [Time].[Time].[1997].[Q2]}"
                }
                """;
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertTrue(
                "compound-slicer drillthrough must NOT error: "
                        + resp.body().substring(0, Math.min(300, resp.body().length())),
                r.path("error").isMissingNode() || r.path("error").isNull());
        JsonNode cellset = r.path("cellset");
        assertTrue("drillthrough should return rows", cellset.isArray() && cellset.size() > 1);

        // Mondrian returns its aggregated compound-slicer projection (grouped
        // rows, no per-fact time columns) — the CONTRACT is that the measure
        // total is exact. FoodMart truth: Drink > Alcoholic Beverages,
        // Store Sales, 1997 Q1+Q2 = 6,588.37.
        int salesCol = -1;
        JsonNode header = cellset.get(0);
        for (int i = 0; i < header.size(); i++) {
            if ("Store Sales".equals(header.get(i).path("value").asText())) {
                salesCol = i;
            }
        }
        assertTrue("Store Sales column must be present in the drillthrough", salesCol >= 0);
        double sum = 0;
        for (int row = 1; row < cellset.size(); row++) {
            sum += Double.parseDouble(
                    cellset.get(row).get(salesCol).path("value").asText());
        }
        assertEquals("compound-slicer drillthrough total must match the cube value", 6588.37, sum, 0.01);
    }

    @Test
    public void compoundSameDimSlicer_wholeQueryShape_succeeds_saiku1714() throws Exception {
        // Same saiku#1714 scenario through the OTHER assembly path —
        // drillthrough(queryName, maxrows, returns) prefixes DRILLTHROUGH onto
        // the full two-axis query MDX, compound WHERE included.
        String body =
                """
                {
                  "name": "drill-1714-whole",
                  "cube": {
                    "connection": "unknown_foodmart",
                    "catalog": "FoodMart",
                    "schema": "FoodMart",
                    "name": "Sales",
                    "uniqueName": "[Sales]"
                  },
                  "mdx": "DRILLTHROUGH MAXROWS 10 SELECT {[Measures].[Store Sales]} ON COLUMNS, {[Product].[Products].[Drink]} ON ROWS FROM [Sales] WHERE {[Time].[Time].[1997].[Q1], [Time].[Time].[1997].[Q2]}"
                }
                """;
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertTrue(
                "whole-query compound-slicer drillthrough must NOT error: "
                        + resp.body().substring(0, Math.min(300, resp.body().length())),
                r.path("error").isMissingNode() || r.path("error").isNull());
        assertTrue(
                "drillthrough should return rows",
                r.path("cellset").isArray() && r.path("cellset").size() > 1);
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
