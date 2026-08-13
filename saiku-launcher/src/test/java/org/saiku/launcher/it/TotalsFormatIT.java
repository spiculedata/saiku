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
 * saiku#1715 — totals must format with the MEASURE's own format string.
 * {@code TotalAggregator} carried a long-abandoned experiment to adopt the
 * per-cell FORMAT_STRING; the resolved contract is constructor-format-only:
 * {@code TotalsListsBuilder.getMeasureFormat} feeds each aggregator the
 * measure's metadata format, and calculated measures carry theirs through
 * their properties map. These pins fail if either flow regresses to raw
 * unformatted totals.
 */
public class TotalsFormatIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    private static final String QUERY_TEMPLATE =
            """
            {
              "name": "%s",
              "type": "QUERYMODEL",
              "cube": {
                "connection": "unknown_foodmart",
                "catalog": "FoodMart",
                "schema": "FoodMart",
                "name": "Sales",
                "uniqueName": "[Sales]"
              },
              "queryModel": {
                "axes": {
                  "FILTER": {"location": "FILTER", "hierarchies": [], "filters": [], "nonEmpty": false},
                  "COLUMNS": {"location": "COLUMNS", "hierarchies": [], "filters": [], "nonEmpty": false},
                  "ROWS": {
                    "location": "ROWS", "nonEmpty": true, "filters": [],
                    "aggregators": ["sum"],
                    "hierarchies": [{
                      "name": "Products", "caption": "Products", "dimension": "Product",
                      "levels": {"Product Family": {
                        "name": "Product Family", "caption": "Product Family",
                        "selection": {"type": "INCLUSION", "members": []}
                      }}
                    }]
                  },
                  "PAGES": {"location": "PAGES", "hierarchies": [], "filters": [], "nonEmpty": false}
                },
                "visualTotals": false,
                "details": {"axis": "COLUMNS", "location": "TOP", "measures": [%s]},
                "calculatedMeasures": [%s],
                "calculatedMembers": []
              }
            }
            """;

    @Test
    public void grandTotal_usesBaseMeasureFormatString_saiku1715() throws Exception {
        // Store Sales is declared with formatString='#,###.00' in FoodMart4.xml.
        // The all-Product-Family grand total is 565,238.13 — the assertion is on
        // the FORMATTED string, so it fails on both a wrong number and a raw
        // unformatted fallback ("565238.13000...").
        String body = QUERY_TEMPLATE.formatted(
                "totals-fmt-base",
                "{\"name\": \"Store Sales\", \"uniqueName\": \"[Measures].[Store Sales]\","
                        + " \"caption\": \"Store Sales\", \"type\": \"EXACT\"}",
                "");
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertTrue(
                "totals query must NOT error: "
                        + resp.body().substring(0, Math.min(300, resp.body().length())),
                r.path("error").isMissingNode() || r.path("error").isNull());
        // saiku#1723: assert the exact formatted string AT THE TOTALS POSITION, not anywhere in the
        // payload. body.contains("565,238.13") would also pass if a data row or some other surface
        // carried that string — the contract is that the GRAND-TOTAL cell is formatted. The grand
        // total lives at rowTotalsLists[0][0].cells[0][0].value (a Total[][] whose first Total's
        // first cell-row holds one cell per details-measure).
        assertEquals(
                "grand total (rowTotalsLists[0][0]) must carry the base measure's #,###.00 format",
                "565,238.13",
                grandTotalCell(r, 0));
    }

    @Test
    public void grandTotal_usesCalculatedMeasureFormatString_saiku1715() throws Exception {
        // A query-defined calculated measure with FORMAT_STRING '0.00%'. Its
        // grand total must render with THAT format (2 * 565,238.13 shown as a
        // percentage = 113047626.00%), proving calc-measure formats flow into
        // totals via the properties map, not just into data cells.
        String body = QUERY_TEMPLATE.formatted(
                "totals-fmt-calc",
                "{\"name\": \"Store Sales\", \"uniqueName\": \"[Measures].[Store Sales]\","
                        + " \"caption\": \"Store Sales\", \"type\": \"EXACT\"},"
                        + "{\"name\": \"Double Sales\", \"uniqueName\": \"[Measures].[Double Sales]\","
                        + " \"caption\": \"Double Sales\", \"type\": \"CALCULATED\"}",
                "{\"name\": \"Double Sales\", \"uniqueName\": \"[Measures].[Double Sales]\","
                        + " \"caption\": \"Double Sales\","
                        + " \"formula\": \"[Measures].[Store Sales] * 2\","
                        + " \"hierarchyName\": \"[Measures]\","
                        + " \"properties\": {\"FORMAT_STRING\": \"0.00%\"}}");
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/query/execute", body);
        assertEquals(200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertTrue(
                "totals query must NOT error: "
                        + resp.body().substring(0, Math.min(300, resp.body().length())),
                r.path("error").isMissingNode() || r.path("error").isNull());
        // saiku#1723: pin BOTH measures' formats at their exact totals positions. The grand-total
        // cell-row carries one cell per details-measure in order: [0] = Store Sales, [1] = Double
        // Sales. Asserting the position (not body.contains) guards against a future surface carrying
        // "0.00%"/"113047626.00%" elsewhere, and against the two totals swapping columns.
        assertEquals(
                "calc-measure grand total (rowTotalsLists[0][0], col 1) must carry its 0.00% format",
                "113047626.00%", grandTotalCell(r, 1));
        assertEquals(
                "base-measure grand total (rowTotalsLists[0][0], col 0) must keep #,###.00 alongside the calc measure",
                "565,238.13",
                grandTotalCell(r, 0));
    }

    /**
     * saiku#1723 — read the grand-total cell for the {@code measureIndex}-th details measure from the
     * parsed response. Path: {@code rowTotalsLists[0][0].cells[0][measureIndex].value}. Fails with a
     * descriptive assertion (rather than an NPE) if any node on the path is missing, so a totals
     * regression surfaces as a readable message.
     */
    private static String grandTotalCell(JsonNode root, int measureIndex) {
        JsonNode rowTotalsLists = root.path("rowTotalsLists");
        assertTrue("response must carry a rowTotalsLists array", rowTotalsLists.isArray() && rowTotalsLists.size() > 0);
        JsonNode grandTotalGroup = rowTotalsLists.path(0);
        assertTrue(
                "rowTotalsLists[0] must be a non-empty Total[]",
                grandTotalGroup.isArray() && grandTotalGroup.size() > 0);
        JsonNode cellRows = grandTotalGroup.path(0).path("cells");
        assertTrue("grand-total Total must carry a cells[][]", cellRows.isArray() && cellRows.size() > 0);
        JsonNode cellRow = cellRows.path(0);
        assertTrue(
                "grand-total cell-row must have a cell for measure index " + measureIndex,
                cellRow.isArray() && cellRow.size() > measureIndex);
        return cellRow.path(measureIndex).path("value").asText();
    }
}
