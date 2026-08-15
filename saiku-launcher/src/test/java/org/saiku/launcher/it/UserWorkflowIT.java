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
 * End-to-end Saiku user workflow ITs. Where the per-resource ITs each pin a
 * specific contract, this class threads several endpoints together the way the
 * SPA actually uses them — so coupling bugs (e.g., schema → query → cellset
 * mismatch) surface even when the individual endpoints look fine in isolation.
 */
public class UserWorkflowIT {

    private static SaikuItHarness harness;
    // Deliberately the pre-saiku#1871 spelling — this whole workflow exercising the legacy
    // `unknown_` name end to end is what proves existing saved content survives the rename.
    private static final String CUBE = "unknown_foodmart/FoodMart/FoodMart/Sales";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void discoverThenAiQuery_endToEnd() throws Exception {
        // Step 1: enumerate cubes via /api/ai/cubes
        HttpResponse<String> cubes = harness.getAuth("/rest/saiku/api/ai/cubes");
        assertEquals(200, cubes.statusCode());
        boolean salesPresent = false;
        for (JsonNode c : harness.parse(cubes)) {
            if ("Sales".equals(c.path("cubeName").asText())) {
                salesPresent = true;
                break;
            }
        }
        assertTrue("Sales cube must be discoverable", salesPresent);

        // Step 2: fetch the cube schema
        HttpResponse<String> schema = harness.getAuth("/rest/saiku/api/ai/schema/" + CUBE);
        assertEquals(200, schema.statusCode());
        JsonNode schemaBody = harness.parse(schema);
        // Read a measure name from the live schema and use it in step 3 to
        // prove the discover → query coupling holds end-to-end.
        JsonNode measures = schemaBody.path("measures");
        assertTrue("schema must expose at least one measure", measures.fields().hasNext());
        String firstMeasureName =
                measures.fields().next().getValue().path("name").asText();
        assertFalse("measure name must be non-blank", firstMeasureName.isBlank());

        // Step 3: query that measure
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "%s"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE, firstMeasureName);
        HttpResponse<String> query = harness.postAuthJson("/rest/saiku/api/ai/query", body);
        assertEquals(200, query.statusCode());
        JsonNode queryBody = harness.parse(query);
        assertEquals("SUCCESS", queryBody.path("status").asText());
        assertEquals(
                "schema-derived query must return all 3 product families",
                3,
                queryBody.path("totalRows").asInt());
    }

    @Test
    public void legacyDiscoverThenLegacyExecute_endToEnd() throws Exception {
        // Walk the older OlapDiscoverResource → Query2Resource path that
        // pre-AiQueryResource clients use.
        HttpResponse<String> conns = harness.getAuth("/rest/saiku/admin/discover");
        assertEquals(200, conns.statusCode());
        boolean foundFoodmart = false;
        for (JsonNode c : harness.parse(conns)) {
            if ("foodmart".equals(c.path("name").asText())) {
                foundFoodmart = true;
                break;
            }
        }
        assertTrue(foundFoodmart);

        // The discover route resolves to a CubeMetadata; we don't read it
        // here. Jump straight to executing a vanilla MDX statement via the
        // /api/query/execute endpoint.
        String mdx =
                """
                {
                  "name": "workflow-it",
                  "cube": {
                    "connection": "unknown_foodmart",
                    "catalog": "FoodMart",
                    "schema": "FoodMart",
                    "name": "Sales",
                    "uniqueName": "[Sales]"
                  },
                  "mdx": "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS, NON EMPTY {[Product].[Products].[Product Family].Members} ON ROWS FROM [Sales]"
                }
                """;
        HttpResponse<String> exec = harness.postAuthJson("/rest/saiku/api/query/execute", mdx);
        assertEquals(200, exec.statusCode());
        JsonNode r = harness.parse(exec);
        assertTrue("cellset shape present", r.has("cellset"));
        assertTrue(r.path("cellset").isArray());
        // header row + 3 product families = 4 rows minimum
        assertTrue(
                "cellset must contain at least header + 3 product families, got "
                        + r.path("cellset").size(),
                r.path("cellset").size() >= 4);
    }
}
