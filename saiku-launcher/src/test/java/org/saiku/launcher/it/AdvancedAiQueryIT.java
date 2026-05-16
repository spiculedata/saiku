/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Coverage for the AI Query API's advanced features — visual totals,
 * Hierarchize-merged same-hierarchy rows, CROSSJOIN cross-axis, explicit
 * member sets, exclusion filters, descendants-of, relative time filters,
 * multi-measure × dim crossjoin cell preservation, and parent-member /
 * cross-level rows. These are the surfaces real Saiku users hit the most
 * but that the simple-happy-path test in {@link AiQueryIT} doesn't exercise.
 */
public class AdvancedAiQueryIT {

    private static SaikuItHarness harness;
    private static final String CUBE = "unknown_foodmart/FoodMart/FoodMart/Sales";
    private static final String QUERY = "/rest/saiku/api/ai/query";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void visualTotalsTrue_emitsVisualtotalsInGeneratedMdx() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Category"}],
                  "visualTotals": true,
                  "limit": 3
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals("expected 200, got " + resp.statusCode() + " body=" + resp.body(), 200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        String mdx = r.path("metadata").path("generatedMdx").asText();
        assertTrue("generated MDX must use VISUALTOTALS, got: " + mdx, mdx.contains("VISUALTOTALS"));
    }

    @Test
    public void sameHierarchyMultiLevelRows_emitsHierarchizeNotCrossjoin() throws Exception {
        // Store State + Store Name share the Stores hierarchy → Hierarchize merge,
        // NOT CROSSJOIN which would multiply rows.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [
                    {"dimension": "Store", "hierarchy": "Stores", "level": "Store State"},
                    {"dimension": "Store", "hierarchy": "Stores", "level": "Store Name"}
                  ],
                  "limit": 4,
                  "nonEmpty": false
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals("expected 200, got " + resp.statusCode() + " body=" + resp.body(), 200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        String mdx = r.path("metadata").path("generatedMdx").asText();
        assertTrue("same-hierarchy multi-level rows must emit Hierarchize, got: " + mdx, mdx.contains("Hierarchize"));
        assertFalse(
                "same-hierarchy rows must NOT emit CROSSJOIN — that would multiply unrelated rows, got: " + mdx,
                mdx.contains("CROSSJOIN"));
    }

    @Test
    public void distinctHierarchyRows_emitsCrossjoin() throws Exception {
        // Time × Product → distinct hierarchies, CROSSJOIN required.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [
                    {"dimension": "Time", "hierarchy": "Time", "level": "Year"},
                    {"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}
                  ],
                  "limit": 6
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        String mdx = r.path("metadata").path("generatedMdx").asText();
        assertTrue("distinct-hierarchy rows must emit CROSSJOIN, got: " + mdx, mdx.contains("CROSSJOIN"));
    }

    @Test
    public void multiMeasureCrossDim_preservesAllCells_saiku789() throws Exception {
        // Regression for saiku#789: 2 measures × Quarter must yield 8
        // distinct columns and the value cells must differ across measures.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}, {"name": "Unit Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "columns": [{"dimension": "Time", "hierarchy": "Time", "level": "Quarter"}]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        assertEquals(
                "2 measures × 4 quarters must yield exactly 8 columns",
                8,
                r.path("metadata").path("columns").size());
        // Values for the same row/quarter must differ across measures —
        // otherwise the cross-join collapsed measures into one cell.
        double storeSalesQ1 =
                r.path("data").get(0).path("Store Sales | Q1").path("value").asDouble();
        double unitSalesQ1 =
                r.path("data").get(0).path("Unit Sales | Q1").path("value").asDouble();
        assertNotEquals("Store Sales Q1 and Unit Sales Q1 must be distinct values", storeSalesQ1, unitSalesQ1, 1e-6);
    }

    @Test
    public void explicitMembersOnRows_returnsOnlyRequestedMembers() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{
                    "dimension": "Product",
                    "hierarchy": "Products",
                    "level": "Product Family",
                    "members": ["[Product].[Products].[Drink]", "[Product].[Products].[Food]"]
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        assertEquals(
                "explicit two-member set must return exactly 2 rows",
                2,
                r.path("totalRows").asInt());
    }

    @Test
    public void filterNotIn_excludesNamedMember() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "filters": [{
                    "dimension": "Store",
                    "hierarchy": "Stores",
                    "level": "Store Country",
                    "op": "not_in",
                    "members": ["[Store].[Stores].[Canada]"]
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        // FoodMart has no data for Canada, so the result count is the same as
        // the full set, but the MDX should encode the exclusion.
        String mdx = r.path("metadata").path("generatedMdx").asText();
        assertTrue(
                "not_in must encode an Except/exclude semantics, got: " + mdx,
                mdx.contains("Except") || mdx.contains("EXCEPT"));
    }

    @Test
    public void filterDescendantsOf_resolvesToDescendantsCall() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "filters": [{
                    "dimension": "Store",
                    "hierarchy": "Stores",
                    "level": "Store Country",
                    "op": "descendants_of",
                    "members": ["[Store].[Stores].[USA]"]
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        assertEquals(
                "descendants_of must still return all 3 product families",
                3,
                r.path("totalRows").asInt());
    }

    @Test
    public void filterBetween_year1997to1998_returnsAllProductFamilies() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Unit Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "filters": [{
                    "dimension": "Time",
                    "hierarchy": "Time",
                    "level": "Year",
                    "op": "between",
                    "members": ["[Time].[Time].[1997]", "[Time].[Time].[1998]"]
                  }],
                  "nonEmpty": false
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        assertEquals(3, r.path("totalRows").asInt());
    }

    @Test
    public void filterRelativeLastNQuarters_emitsTailInMdx() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "filters": [{
                    "dimension": "Time",
                    "hierarchy": "Time",
                    "level": "Quarter",
                    "op": "relative",
                    "value": "last_n_quarters",
                    "n": 2
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        String mdx = r.path("metadata").path("generatedMdx").asText();
        assertTrue("relative last_n_quarters must emit Tail(...), got: " + mdx, mdx.contains("Tail"));
    }

    @Test
    public void multipleFiltersOnSameHierarchy_rejectedAsValidationError() throws Exception {
        // Two filters on the Time hierarchy at different levels must be
        // rejected — Mondrian can't combine them into a coherent slicer set.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "filters": [
                    {"dimension": "Time", "hierarchy": "Time", "level": "Year", "members": ["[Time].[Time].[1997]"]},
                    {"dimension": "Time", "hierarchy": "Time", "level": "Quarter", "members": ["[Time].[Time].[1997].[Q1]"]}
                  ]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
        assertTrue(
                "error message should call out the hierarchy collision, got: "
                        + r.path("error").asText(),
                r.path("error").asText().contains("Multiple filters")
                        || r.path("error").asText().contains("hierarchy"));
    }

    @Test
    public void axisHierarchyReusedInFilter_rejectedSaiku784() throws Exception {
        // Regression for saiku#784: if a hierarchy is already on rows/columns,
        // also using it in a filter is rejected.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Unit Sales"}],
                  "rows": [{"dimension": "Customer", "hierarchy": "Customers", "level": "City"}],
                  "filters": [{
                    "dimension": "Customer",
                    "hierarchy": "Customers",
                    "level": "State Province",
                    "op": "descendants_of",
                    "members": ["[Customer].[Customers].[USA].[CA]"]
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
        assertTrue(
                "error must mention axis-reuse, got: " + r.path("error").asText(),
                r.path("error").asText().contains("already on the rows/columns axis"));
    }

    @Test
    public void memberAtWrongLevel_rejectedSaiku790() throws Exception {
        // Regression for saiku#790: claiming a Product Department member at
        // the Product Family level must be flagged with the correct level
        // name in the error.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{
                    "dimension": "Product",
                    "hierarchy": "Products",
                    "level": "Product Family",
                    "members": ["[Product].[Products].[Drink].[Beverages]"]
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
        String err = r.path("error").asText();
        assertTrue(
                "error must mention both Product Department (actual) and Product Family (requested), got: " + err,
                err.contains("Product Department") && err.contains("Product Family"));
    }

    @Test
    public void duplicateMeasures_rejectedSaiku796() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}, {"name": "Store Sales"}, {"name": "Unit Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
        assertEquals("measures", r.path("field").asText());
    }

    @Test
    public void invalidOrderDirection_rejectedWithAvailable() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "order": [{"by": "Store Sales", "direction": "sideways"}],
                  "limit": 2
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
        assertEquals("order[0].direction", r.path("field").asText());
        // 'available' should list only asc/desc.
        boolean hasAsc = false;
        boolean hasDesc = false;
        for (JsonNode v : r.path("available")) {
            if ("asc".equals(v.asText())) hasAsc = true;
            if ("desc".equals(v.asText())) hasDesc = true;
        }
        assertTrue("available must list asc + desc", hasAsc && hasDesc);
    }

    @Test
    public void nonexistentMember_returnsValidationErrorNotMondrianException() throws Exception {
        // A made-up member name must be caught client-side and surfaced as
        // a typed 400 envelope, not a Mondrian 500 stack trace.
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{
                    "dimension": "Product",
                    "hierarchy": "Products",
                    "level": "Product Family",
                    "members": ["[Product].[Products].[Drink]", "[Product].[Products].[Pizza]"]
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
        assertEquals("members", r.path("field").asText());
        assertTrue(
                "error must name the offending member, got: " + r.path("error").asText(),
                r.path("error").asText().contains("Pizza"));
    }
}
