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
 * Coverage for the legacy MDX query surface ({@code /rest/saiku/api/query/*}).
 * The SPA uses {@code /execute} to run raw MDX strings and {@code /enrich}
 * to project a query model onto axes for visualisation.
 *
 * <p>FoodMart Sales cube is the workhorse — every query body references it
 * via the seed datasource catalog.
 */
public class Query2ResourceIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void executeMdx_returnsCellSetWithMetadata() throws Exception {
        String body =
                """
                {
                  "name": "it-execute-1",
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
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals("expected 200, got " + resp.statusCode() + " body=" + resp.body(), 200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        // Returned QueryResult has cellset + query metadata.
        assertTrue("response should have a cellset field", r.has("cellset"));
        assertTrue("cellset must be a JSON array", r.path("cellset").isArray());
        assertTrue(
                "cellset must contain at least header + 3 product families",
                r.path("cellset").size() >= 4);
    }

    @Test
    public void executeMdx_unknownMeasure_returnsQueryResultWithErrorField() throws Exception {
        // saiku#1865: a measure that does not exist never reaches the warehouse — Mondrian fails
        // to resolve it — so this is the caller's mistake and reports 400. The BODY is unchanged:
        // still a QueryResult whose 'error' carries the underlying message, which the SPA renders
        // as an inline error. Before #1865 this answered 200, which made the failure invisible to
        // anything reasoning about transport.
        String body =
                """
                {
                  "name": "it-execute-bad",
                  "cube": {
                    "connection": "unknown_foodmart",
                    "catalog": "FoodMart",
                    "schema": "FoodMart",
                    "name": "Sales",
                    "uniqueName": "[Sales]"
                  },
                  "mdx": "SELECT {[Measures].[Made Up Measure]} ON COLUMNS FROM [Sales]"
                }
                """;
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals("an unresolvable measure is the caller's mistake", 400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertFalse(
                "response should carry an 'error' field for bad MDX",
                r.path("error").isMissingNode());
        assertTrue(
                "error must mention the bad measure name",
                r.path("error").asText().contains("Made Up Measure"));
    }

    @Test
    public void cancelNonexistentQuery_is200_idempotent() throws Exception {
        // Cancel is idempotent at the service layer — cancelling a name with
        // no live cellset is a no-op rather than an error. Pin that contract
        // so an accidental tighten-to-404 is caught.
        HttpResponse<String> resp = harness.deleteAuth("/rest/saiku/api/query/no-such-query/cancel");
        assertEquals(200, resp.statusCode());
    }
}
