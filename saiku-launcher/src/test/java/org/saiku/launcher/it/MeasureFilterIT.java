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
 * saiku#1721 — end-to-end CALL-SITE cover for the Measure-flavour axis filter.
 *
 * <p>Before saiku#1717 the {@code case Measure:} arm of {@code Fat.convertFilters} was an empty
 * stub, so a Measure-flavour filter posted on a QUERYMODEL axis was SILENTLY DROPPED and the query
 * ran unconstrained. The 17 {@code FatMeasureFilterTest} helper tests never touched the call site,
 * so a revert of the wiring stays green there — this IT is the missing end-to-end proof.
 *
 * <p>FoodMart Sales, ROWS = Product Family (Drink / Food / Non-Consumable). Store Sales by family:
 * Drink 48,836.21, Food 409,035.59, Non-Consumable 107,366.33. A Measure filter
 * {@code Store Sales > 200000} keeps ONLY Food. Unfiltered the same query returns all three — the
 * pre-fix bug returned all three even WITH the filter. So: three rows unfiltered, exactly one
 * (Food) filtered.
 */
public class MeasureFilterIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    /** ROWS = Product Family, Store Sales on details. {@code %s} is the ROWS-axis filters array. */
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
                    "location": "ROWS", "nonEmpty": true, "filters": [%s],
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
                "details": {"axis": "COLUMNS", "location": "TOP", "measures": [
                  {"name": "Store Sales", "uniqueName": "[Measures].[Store Sales]",
                   "caption": "Store Sales", "type": "EXACT"}
                ]},
                "calculatedMeasures": [],
                "calculatedMembers": []
              }
            }
            """;

    private static final String MEASURE_FILTER = "{\"flavour\": \"Measure\", \"operator\": \"GREATER\","
            + " \"expressions\": [\"[Measures].[Store Sales]\", \"200000\"]}";

    /** Count the Product-Family rows in the cellset (data rows carry a member caption in col 0). */
    private static int productFamilyRowCount(JsonNode cellset) {
        int count = 0;
        for (int i = 1; i < cellset.size(); i++) { // row 0 is the header
            String c0 = cellset.get(i).get(0).path("value").asText();
            if ("Drink".equals(c0) || "Food".equals(c0) || "Non-Consumable".equals(c0)) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void measureFilterOnRowsAxis_constrainsResult_saiku1721() throws Exception {
        // Baseline: no filter -> all three product families come back.
        String unfilteredBody = QUERY_TEMPLATE.formatted("measure-filter-none", "");
        HttpResponse<String> unfiltered = harness.postAuthJson("/rest/saiku/api/query/execute", unfilteredBody);
        assertEquals(200, unfiltered.statusCode());
        JsonNode ur = harness.parse(unfiltered);
        assertTrue(
                "unfiltered query must NOT error: "
                        + unfiltered
                                .body()
                                .substring(0, Math.min(300, unfiltered.body().length())),
                ur.path("error").isMissingNode() || ur.path("error").isNull());
        JsonNode unfilteredCells = ur.path("cellset");
        assertEquals(
                "unfiltered Product Family query should return all three families",
                3,
                productFamilyRowCount(unfilteredCells));

        // Filtered: Store Sales > 200000 keeps only Food. Pre-fix this was silently dropped and
        // still returned all three — this is the assertion that fails if the call site regresses.
        String filteredBody = QUERY_TEMPLATE.formatted("measure-filter-food", MEASURE_FILTER);
        HttpResponse<String> filtered = harness.postAuthJson("/rest/saiku/api/query/execute", filteredBody);
        assertEquals(200, filtered.statusCode());
        JsonNode fr = harness.parse(filtered);
        assertTrue(
                "filtered query must NOT error: "
                        + filtered.body()
                                .substring(0, Math.min(300, filtered.body().length())),
                fr.path("error").isMissingNode() || fr.path("error").isNull());
        JsonNode filteredCells = fr.path("cellset");
        assertEquals(
                "Measure filter Store Sales > 200000 must keep exactly one Product Family row",
                1,
                productFamilyRowCount(filteredCells));
        assertTrue(
                "the surviving row must be Food (the only family over 200000 Store Sales)",
                filtered.body().contains("Food"));
        assertFalse("Drink must be filtered out", rowPresent(filteredCells, "Drink"));
        assertFalse("Non-Consumable must be filtered out", rowPresent(filteredCells, "Non-Consumable"));
    }

    private static boolean rowPresent(JsonNode cellset, String family) {
        for (int i = 1; i < cellset.size(); i++) {
            if (family.equals(cellset.get(i).get(0).path("value").asText())) {
                return true;
            }
        }
        return false;
    }
}
