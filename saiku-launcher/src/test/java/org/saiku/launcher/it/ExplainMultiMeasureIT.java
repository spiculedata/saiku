/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#2192 — "explain this number" must name the measure of the <em>clicked</em> cell, and must
 * never do arithmetic across different measures.
 *
 * <p>The unit tests use single-measure fakes, which is exactly why they could not see this: with
 * two measures on the columns, explain used to name the first measure for every cell, and the
 * row-wise drivers added a Unit Sales cell to a Store Sales cell. This goes through the real
 * endpoint against FoodMart with the issue's own two-measure query.
 */
public class ExplainMultiMeasureIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    private static final String EXECUTE =
            """
            {
              "name": "it-explain-multimeasure",
              "cube": {
                "connection": "unknown_foodmart",
                "catalog": "FoodMart",
                "schema": "FoodMart",
                "name": "Sales",
                "uniqueName": "[Sales]"
              },
              "mdx": "SELECT NON EMPTY {[Measures].[Unit Sales], [Measures].[Store Sales]} ON COLUMNS, NON EMPTY {[Product].[Products].[Product Family].Members} ON ROWS FROM [Sales]"
            }
            """;

    /**
     * The explain endpoint reads the query the SAME session ran, and the shared harness client keeps
     * no cookies, so this test brings a client that does.
     */
    private static HttpResponse<String> post(HttpClient session, String path, String body) throws Exception {
        return session.send(
                HttpRequest.newBuilder(URI.create(harness.baseUrl() + path))
                        .header("Authorization", harness.adminBasicAuth())
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode explain(HttpClient session, int row, int column) throws Exception {
        HttpResponse<String> executed = post(
                session,
                "/rest/saiku/api/query/execute",
                EXECUTE.replace("it-explain-multimeasure", "it-explain-multimeasure-" + row + "-" + column));
        assertEquals("execute failed: " + executed.body(), 200, executed.statusCode());
        HttpResponse<String> resp = post(
                session,
                "/rest/saiku/api/ai/explain",
                """
                {"queryName":"it-explain-multimeasure-%d-%d","position":{"row":%d,"column":%d},"includeSql":false}
                        """
                        .formatted(row, column, row, column));
        assertEquals("explain failed: " + resp.body(), 200, resp.statusCode());
        return harness.parse(resp);
    }

    @Test
    public void explainsEachCellUnderTheMeasureThatWasClicked() throws Exception {
        HttpClient session =
                HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        JsonNode storeSales = explain(session, 1, 1);
        assertEquals("Store Sales", storeSales.path("measure").asText());
        assertTrue(
                "narrative must name the clicked measure: "
                        + storeSales.path("narrative").asText(),
                storeSales.path("narrative").asText().startsWith("Store Sales for Food"));

        JsonNode unitSales = explain(session, 0, 0);
        assertEquals("Unit Sales", unitSales.path("measure").asText());
        assertTrue(
                "narrative must name the clicked measure: "
                        + unitSales.path("narrative").asText(),
                unitSales.path("narrative").asText().startsWith("Unit Sales for Drink"));
    }

    /**
     * A row holds one Unit Sales cell and one Store Sales cell: no total, rank of "the largest" or
     * any other sentence may add or compare across the two. The row-wise drivers (SHARE_OF_ROW,
     * ROW_PEAK) must be absent — the row holds a single cell of the clicked measure — and the
     * narrative must not name the other measure's figure.
     */
    @Test
    public void noSentenceMixesTheTwoMeasures() throws Exception {
        HttpClient session =
                HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        JsonNode storeSales = explain(session, 1, 1);
        String narrative = storeSales.path("narrative").asText();
        assertFalse("narrative must not name the other measure: " + narrative, narrative.contains("Unit Sales"));
        assertFalse("narrative must not claim a row total: " + narrative, narrative.contains("row total"));
        assertFalse(
                "narrative must not compare to the other cell: " + narrative,
                narrative.contains("largest cell in the same row"));
        assertFalse(
                "the row total driver must be absent: " + storeSales.path("drivers"),
                storeSales.path("drivers").toString().contains("SHARE_OF_ROW"));
        assertFalse(
                "the row peak driver must be absent: " + storeSales.path("drivers"),
                storeSales.path("drivers").toString().contains("ROW_PEAK"));

        JsonNode unitSales = explain(session, 0, 0);
        String unitsNarrative = unitSales.path("narrative").asText();
        assertFalse(
                "narrative must not name the other measure: " + unitsNarrative, unitsNarrative.contains("Store Sales"));
        assertFalse("narrative must not claim a row total: " + unitsNarrative, unitsNarrative.contains("row total"));
    }
}
