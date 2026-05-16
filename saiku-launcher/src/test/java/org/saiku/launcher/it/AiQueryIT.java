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
 * End-to-end integration tests for the AI Query API surface ({@code
 * /rest/saiku/api/ai/*}). Mirrors a meaningful subset of
 * {@code saiku-launcher/test-ai-live.sh} as JUnit assertions, so the contract
 * is enforced by {@code mvn verify -P integration} rather than only by an
 * out-of-band shell script.
 *
 * <p>Boots once via {@link SaikuItHarness}; assumes the seeded FoodMart H2
 * datasource that the launcher ships.
 */
public class AiQueryIT {

    private static final String CUBE = "unknown_foodmart/FoodMart/FoodMart/Sales";
    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    // ---------------------------------------------------------------- discovery

    @Test
    public void cubes_listIncludesSales() throws Exception {
        HttpResponse<String> resp = harness.getAuth("/rest/saiku/api/ai/cubes");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue("response must be a JSON array", body.isArray());

        boolean foundSales = false;
        for (JsonNode c : body) {
            if ("Sales".equals(c.path("cubeName").asText())) {
                foundSales = true;
                break;
            }
        }
        assertTrue("Sales cube must be present in /ai/cubes — body: " + resp.body(), foundSales);
    }

    @Test
    public void schema_SalesCubeHasProductAndStoreSales() throws Exception {
        HttpResponse<String> resp = harness.getAuth("/rest/saiku/api/ai/schema/" + CUBE);
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue(
                "schema must include 'product' dimension",
                body.path("dimensions").has("product"));
        assertTrue(
                "schema must include 'store sales' measure",
                body.path("measures").has("store sales"));
    }

    @Test
    public void schema_threeSegmentCubeId_returns400WithCubeIdField() throws Exception {
        HttpResponse<String> resp = harness.getAuth("/rest/saiku/api/ai/schema/foo/bar/baz");
        assertEquals(400, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", body.path("status").asText());
        assertEquals("cubeId", body.path("field").asText());
    }

    @Test
    public void schema_unknownCubeName_returnsAvailableList() throws Exception {
        HttpResponse<String> resp =
                harness.getAuth("/rest/saiku/api/ai/schema/unknown_foodmart/FoodMart/FoodMart/Nonsense");
        assertEquals(400, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", body.path("status").asText());
        assertEquals("cube", body.path("field").asText());

        boolean salesIsAnAvailableOption = false;
        for (JsonNode candidate : body.path("available")) {
            if ("Sales".equals(candidate.asText())) {
                salesIsAnAvailableOption = true;
                break;
            }
        }
        assertTrue("Sales must be listed in the 'available' candidates", salesIsAnAvailableOption);
    }

    // ---------------------------------------------------------------- happy paths

    @Test
    public void query_simpleMeasureByProductFamily_returns3Rows() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/ai/query", body);
        assertEquals("expected 200, got " + resp.statusCode() + " body=" + resp.body(), 200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        assertEquals(3, r.path("totalRows").asInt());
        double firstValue =
                r.path("data").get(0).path("Store Sales").path("value").asDouble();
        assertTrue("first row Store Sales must be > 0, got " + firstValue, firstValue > 0);
    }

    @Test
    public void query_orderAndLimit_emitsTopCountAndReturnsLimitedRows() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "order": [{"by": "Store Sales", "direction": "desc"}],
                  "limit": 2
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/ai/query", body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        assertEquals(2, r.path("totalRows").asInt());
        assertTrue(
                "generatedMdx should use TopCount: "
                        + r.path("metadata").path("generatedMdx").asText(),
                r.path("metadata").path("generatedMdx").asText().contains("TopCount"));
    }

    @Test
    public void query_filterIn_singleYearFilter_returnsAllProductFamilies() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}],
                  "filters": [{
                    "dimension": "Time",
                    "hierarchy": "Time",
                    "level": "Year",
                    "members": ["[Time].[Time].[1997]"]
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/ai/query", body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("SUCCESS", r.path("status").asText());
        assertEquals(3, r.path("totalRows").asInt());
    }

    // ---------------------------------------------------------------- validation

    @Test
    public void query_unknownMeasure_returnsValidationErrorWithAvailable() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Made Up Measure"}],
                  "rows": []
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/ai/query", body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
        assertEquals("measures[].name", r.path("field").asText());

        boolean storeSalesListed = false;
        for (JsonNode candidate : r.path("available")) {
            if ("Store Sales".equals(candidate.asText())) {
                storeSalesListed = true;
                break;
            }
        }
        assertTrue("'Store Sales' must be in 'available' for self-correction", storeSalesListed);
    }

    @Test
    public void query_mdxInjectionInMembers_rejectedWithEmbeddedMdxMessage() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{
                    "dimension": "Product",
                    "hierarchy": "Products",
                    "level": "Product Family",
                    "members": ["[Product].[Products].[Drink], Crossjoin([Time].[1997])"]
                  }]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/ai/query", body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
        assertEquals("rows[0].members[0]", r.path("field").asText());
        assertTrue(
                "error message must mention 'Embedded MDX' — got: "
                        + r.path("error").asText(),
                r.path("error").asText().contains("Embedded MDX"));
    }
}
