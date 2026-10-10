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
 * saiku#2193 — "explain this number" must return the SQL Mondrian ran for the cell.
 *
 * <p>{@code SqlCaptureProbe} used to listen on {@code java.util.logging}, which Mondrian never
 * writes to (it logs through slf4j, bound to log4j2), so the unit tests — which published their own
 * JUL records — passed while the real endpoint returned no {@code sql} and no explanation. This
 * goes through the real endpoint against FoodMart so that cannot happen again. Run it with
 * {@code -Dmondrian.backend=legacy} to cover the legacy planner as well as the default (Calcite).
 */
public class ExplainSqlCaptureIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    private static final String EXECUTE =
            """
            {
              "name": "it-explain-sql",
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

    @Test
    public void explainReturnsTheGeneratedSqlForACell() throws Exception {
        HttpClient session =
                HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> executed = post(session, "/rest/saiku/api/query/execute", EXECUTE);
        assertEquals("execute failed: " + executed.body(), 200, executed.statusCode());

        HttpResponse<String> resp = post(
                session,
                "/rest/saiku/api/ai/explain",
                """
                {"queryName":"it-explain-sql","position":{"row":0,"column":0},"includeSql":true}
                """);
        assertEquals("explain failed: " + resp.body(), 200, resp.statusCode());
        JsonNode r = harness.parse(resp);

        JsonNode sql = r.path("sql");
        JsonNode notes = r.path("notes");
        assertTrue(
                "sql must be present; notes=" + notes,
                sql.isTextual() && !sql.asText().isBlank());
        assertTrue(
                "captured text must be a SELECT: " + sql.asText(),
                sql.asText().toLowerCase().startsWith("select"));
        assertFalse(
                "when SQL is captured there must be no 'not captured' note: " + notes,
                notes.toString().contains("SQL was not captured"));
    }
}
