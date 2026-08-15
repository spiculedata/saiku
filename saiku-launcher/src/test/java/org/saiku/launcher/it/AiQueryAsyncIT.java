/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Coverage for the AI Query API's fire-and-poll async endpoints
 * ({@code /rest/saiku/api/ai/query/execute-async}, status, result, cancel,
 * drillthrough columns).
 */
public class AiQueryAsyncIT {

    private static SaikuItHarness harness;
    private static final String CUBE = "unknown_foodmart/FoodMart/FoodMart/Sales";
    private static final String BASE = "/rest/saiku/api/ai";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
        warmCube();
    }

    /**
     * saiku#1849: warm the Sales cube with a cheap SYNCHRONOUS query BEFORE any timed async
     * assertion runs. On a cold launcher the very first query against a cube pays for Mondrian's
     * Calcite backend JIT-compiling the metadata provider (a one-shot, multi-second cost). If that
     * cold-compile lands inside the 30s polled window of {@link #asyncQueryRoundTrip_submitPollResult},
     * the async task can miss the deadline (or fail outright) and the IT reds intermittently on CI —
     * a re-run then clears it. Paying the cold-start here, synchronously and un-timed, means the
     * subsequent async path measures steady-state execution, not first-query compilation. The sync
     * {@code /ai/query} endpoint drives the identical cube/plan through the same Mondrian stack, so
     * the warm-up is representative. Failures here are non-fatal: if the warm-up itself errors we
     * let the actual test surface the real diagnostic rather than masking it in @BeforeClass.
     */
    private static void warmCube() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE);
        try {
            harness.postAuthJson(BASE + "/query", body);
        } catch (Exception ignored) {
            // Warm-up is best-effort; the timed test below owns the real assertion + diagnostics.
        }
    }

    @Test
    public void asyncQueryRoundTrip_submitPollResult() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> submit = harness.postAuthJson(BASE + "/query/execute-async", body);
        assertEquals(
                "submit should be 202/200, got " + submit.statusCode() + " body=" + submit.body(),
                202,
                submit.statusCode());
        JsonNode submitBody = harness.parse(submit);
        String queryId = submitBody.path("queryId").asText();
        assertFalse("queryId must be present on submit", queryId.isBlank());

        // Poll until DONE. The cube is warmed in @BeforeClass, so this window measures steady-state
        // async execution rather than Mondrian/Calcite cold-compile (saiku#1849). Deadline is 60s
        // to give slow CI runners headroom above the warmed steady-state cost — comfortably under
        // the 2-minute failsafe budget.
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        String lastStatus = null;
        String lastError = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> status = harness.getAuth(BASE + "/query/status/" + queryId);
            assertEquals(200, status.statusCode());
            JsonNode statusBody = harness.parse(status);
            lastStatus = statusBody.path("status").asText();
            lastError = statusBody.path("error").asText(null);
            if ("DONE".equals(lastStatus) || "FAILED".equals(lastStatus) || "CANCELLED".equals(lastStatus)) {
                break;
            }
            Thread.sleep(100);
        }
        // Surface the async error body on a terminal FAILED so a genuine regression is distinguishable
        // from a flake (saiku#1849) — never silently retried away.
        assertEquals(
                "query should complete with DONE, last status was " + lastStatus
                        + (lastError != null ? " (error: " + lastError + ")" : ""),
                "DONE",
                lastStatus);

        // Fetch result.
        HttpResponse<String> result = harness.getAuth(BASE + "/query/result/" + queryId);
        assertEquals(200, result.statusCode());
        JsonNode r = harness.parse(result);
        assertEquals("SUCCESS", r.path("status").asText());
        assertEquals(3, r.path("totalRows").asInt());
    }

    @Test
    public void asyncQuery_cancelSucceedsImmediately() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> submit = harness.postAuthJson(BASE + "/query/execute-async", body);
        assertEquals(202, submit.statusCode());
        String queryId = harness.parse(submit).path("queryId").asText();

        HttpResponse<String> cancel = harness.deleteAuth(BASE + "/query/" + queryId);
        // 200 OK regardless of whether the underlying task had already completed.
        assertEquals(
                "cancel should be 200, got " + cancel.statusCode() + " body=" + cancel.body(),
                200,
                cancel.statusCode());
    }

    @Test
    public void asyncStatus_unknownId_returns404() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/query/status/no-such-id");
        assertEquals(404, resp.statusCode());
    }

    @Test
    public void asyncResult_unknownId_returns404() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/query/result/no-such-id");
        assertEquals(404, resp.statusCode());
    }
}
