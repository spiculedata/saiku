/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
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
                  "mdx": "DRILLTHROUGH MAXROWS 99999 SELECT {[Measures].[Store Sales]} ON COLUMNS, {[Product].[Products].[Drink]} ON ROWS FROM [Sales] WHERE {[Time].[Time].[1997].[Q1], [Time].[Time].[1997].[Q2]}"
                }
                """;
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertTrue(
                "whole-query compound-slicer drillthrough must NOT error: "
                        + resp.body().substring(0, Math.min(300, resp.body().length())),
                r.path("error").isMissingNode() || r.path("error").isNull());
        JsonNode cellset = r.path("cellset");
        assertTrue("drillthrough should return rows", cellset.isArray() && cellset.size() > 1);

        // saiku#1722: this test previously asserted only 200/no-error/rows>1, which passes even if
        // the compound slicer silently applies the WRONG set of quarters (or drops one). Raise the
        // MAXROWS cap so the full fact set comes back, then pin the exact Store Sales total for the
        // Drink family, 1997 Q1+Q2 = 23,500.38 (verified against the cube). A regression that
        // returns rows for the wrong slice now trips here too. Store Sales is the last fact column.
        int lastCol = cellset.get(1).size() - 1;
        assertEquals(
                "last drillthrough column must be Store Sales",
                "Store Sales",
                cellset.get(0).get(lastCol).path("value").asText());
        double sum = 0;
        for (int row = 1; row < cellset.size(); row++) {
            sum += Double.parseDouble(
                    cellset.get(row).get(lastCol).path("value").asText());
        }
        assertEquals(
                "whole-query compound-slicer drillthrough total (Drink family, Q1+Q2) must match the cube",
                23500.38,
                sum,
                0.01);
    }

    @Test
    public void compoundSameDimSlicer_cellDrillthrough_fromQueryModel_pinsTotal_saiku1722() throws Exception {
        // saiku#1722: the other compound-slicer tests post hand-written DRILLTHROUGH MDX, which
        // pins Mondrian's execution, not Saiku's ASSEMBLY. This one drives the real cell-drillthrough
        // assembly path end to end: a QUERYMODEL query with two same-dimension filter members
        // (1997 Q1 + Q2 on the Time slicer), executed to register the named query + cellset, then
        // the actual cell-drillthrough endpoint (thinQueryService.drillthroughWithCaptions ->
        // drillthrough(name, cellPosition, ...)). The query registry + cellset context are
        // session-scoped, so we capture the JSESSIONID from the execute and replay it on the
        // drillthrough (the harness HttpClient has no cookie jar) — otherwise the drillthrough
        // lands on a fresh session with an empty context.
        String name = "dt-1722-cell-" + System.nanoTime();
        String body =
                """
                {
                  "name": "%s",
                  "type": "QUERYMODEL",
                  "cube": {"connection": "unknown_foodmart", "catalog": "FoodMart", "schema": "FoodMart",
                           "name": "Sales", "uniqueName": "[Sales]"},
                  "queryModel": {
                    "axes": {
                      "FILTER": {"location": "FILTER", "nonEmpty": false, "filters": [], "hierarchies": [{
                        "name": "Time", "caption": "Time", "dimension": "Time",
                        "levels": {"Quarter": {"name": "Quarter", "caption": "Quarter",
                          "selection": {"type": "INCLUSION", "members": [
                            {"name": "Q1", "uniqueName": "[Time].[Time].[1997].[Q1]", "caption": "Q1"},
                            {"name": "Q2", "uniqueName": "[Time].[Time].[1997].[Q2]", "caption": "Q2"}
                          ]}}}
                      }]},
                      "COLUMNS": {"location": "COLUMNS", "nonEmpty": false, "filters": [], "hierarchies": []},
                      "ROWS": {"location": "ROWS", "nonEmpty": true, "filters": [], "hierarchies": [{
                        "name": "Products", "caption": "Products", "dimension": "Product",
                        "levels": {"Product Category": {"name": "Product Category", "caption": "Product Category",
                          "selection": {"type": "INCLUSION", "members": [
                            {"name": "Alcoholic Beverages",
                             "uniqueName": "[Product].[Products].[Drink].[Alcoholic Beverages]",
                             "caption": "Alcoholic Beverages"}
                          ]}}}
                      }]},
                      "PAGES": {"location": "PAGES", "nonEmpty": false, "filters": [], "hierarchies": []}
                    },
                    "visualTotals": false,
                    "details": {"axis": "COLUMNS", "location": "TOP", "measures": [
                      {"name": "Store Sales", "uniqueName": "[Measures].[Store Sales]",
                       "caption": "Store Sales", "type": "EXACT"}
                    ]},
                    "calculatedMeasures": [], "calculatedMembers": []
                  }
                }
                """
                        .formatted(name);

        // Execute the QUERYMODEL and keep the session that owns the named query + cellset.
        HttpResponse<String> exec = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals("execute must be 200, body=" + exec.body(), 200, exec.statusCode());
        JsonNode er = harness.parse(exec);
        assertTrue(
                "execute must NOT error: "
                        + exec.body().substring(0, Math.min(300, exec.body().length())),
                er.path("error").isMissingNode() || er.path("error").isNull());
        // Sanity: the single compound-slicer cell is the 6,588.37 Q1+Q2 total.
        assertTrue(
                "the executed cell must carry the compound-slicer total 6,588.37",
                exec.body().contains("6,588.37") || exec.body().contains("6588.37"));
        String cookie = sessionCookieOf(exec);
        assertNotNull(
                "execute must establish a JSESSIONID — the query registry + cellset context are"
                        + " session-scoped and the cell-drillthrough below reads them by name",
                cookie);

        // Drill through the (Alcoholic Beverages, Store Sales) cell at position 0:0 — this is the
        // REAL cell-drillthrough assembly path, not raw DRILLTHROUGH MDX.
        HttpRequest drillReq = HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/rest/saiku/api/query/" + name
                        + "/drillthrough?maxrows=99999&position=0:0"))
                .header("Authorization", harness.adminBasicAuth())
                .header("Accept", "application/json")
                .header("Cookie", cookie)
                .GET()
                .build();
        HttpResponse<String> drill = harness.send(drillReq);
        assertEquals("cell-drillthrough must be 200, body=" + drill.body(), 200, drill.statusCode());
        JsonNode dr = harness.parse(drill);
        JsonNode cellset = dr.path("cellset");
        assertTrue("cell-drillthrough should return fact rows", cellset.isArray() && cellset.size() > 1);

        // The default (no `returns`) drillthrough dumps every fact column; Store Sales is the last
        // column of each fact row. Its sum across the fact rows must equal the compound-slicer cell.
        int lastCol = cellset.get(1).size() - 1;
        double sum = 0;
        for (int row = 1; row < cellset.size(); row++) {
            sum += Double.parseDouble(
                    cellset.get(row).get(lastCol).path("value").asText());
        }
        assertEquals(
                "cell-drillthrough Store Sales must sum to the compound-slicer cell total (Q1+Q2)", 6588.37, sum, 0.01);
    }

    /** Extract "JSESSIONID=..." from a Set-Cookie response header, if present. */
    private static String sessionCookieOf(HttpResponse<String> resp) {
        Optional<String> setCookie = resp.headers().allValues("set-cookie").stream()
                .filter(v -> v.toUpperCase().startsWith("JSESSIONID="))
                .findFirst();
        return setCookie.map(v -> v.split(";", 2)[0]).orElse(null);
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
