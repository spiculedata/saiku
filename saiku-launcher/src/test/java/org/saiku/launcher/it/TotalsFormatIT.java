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
        assertTrue(
                "grand total must carry the measure's #,###.00 format, body=" + totalsSnippet(resp.body()),
                resp.body().contains("565,238.13"));
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
        assertTrue(
                "calc-measure grand total must carry its 0.00% format, body=" + totalsSnippet(resp.body()),
                resp.body().contains("113047626.00%"));
        // The base measure's total must still be there, formatted its own way.
        assertTrue(
                "base-measure grand total must keep #,###.00 alongside the calc measure, body="
                        + totalsSnippet(resp.body()),
                resp.body().contains("565,238.13"));
    }

    /** Trim assertion output to the totals region rather than dumping the whole cellset. */
    private static String totalsSnippet(String body) {
        int idx = body.indexOf("rowTotalsLists");
        if (idx < 0) {
            return body.substring(0, Math.min(400, body.length()));
        }
        return body.substring(idx, Math.min(idx + 400, body.length()));
    }
}
