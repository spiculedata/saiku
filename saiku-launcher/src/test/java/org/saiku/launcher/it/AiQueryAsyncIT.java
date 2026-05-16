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

        // Poll until DONE.
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        String lastStatus = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> status = harness.getAuth(BASE + "/query/status/" + queryId);
            assertEquals(200, status.statusCode());
            lastStatus = harness.parse(status).path("status").asText();
            if ("DONE".equals(lastStatus) || "FAILED".equals(lastStatus) || "CANCELLED".equals(lastStatus)) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals("query should complete with DONE, last status was " + lastStatus, "DONE", lastStatus);

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
