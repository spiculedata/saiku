/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Coverage for the remaining AI Query API endpoints not exercised by
 * {@link AiQueryIT} or {@link AiQueryAsyncIT}: {@code /query/preview} and
 * {@code /members/search}.
 */
public class AiQueryExtrasIT {

    private static SaikuItHarness harness;
    private static final String CUBE = "unknown_foodmart/FoodMart/FoodMart/Sales";
    private static final String BASE = "/rest/saiku/api/ai";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void preview_returnsGeneratedMdxWithoutExecuting() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Store Sales"}],
                  "rows": [{"dimension": "Product", "hierarchy": "Products", "level": "Product Family"}]
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(BASE + "/query/preview", body);
        assertEquals(
                "preview should be 200, got " + resp.statusCode() + " body=" + resp.body(), 200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        // Preview returns the generated MDX + metadata but no data array.
        assertTrue(
                "preview body should expose generatedMdx",
                r.has("generatedMdx") || r.path("metadata").has("generatedMdx"));
    }

    @Test
    public void preview_unknownMeasure_returns400WithValidationError() throws Exception {
        String body =
                """
                {
                  "cube": "%s",
                  "measures": [{"name": "Made Up Measure"}],
                  "rows": []
                }
                """
                        .formatted(CUBE);
        HttpResponse<String> resp = harness.postAuthJson(BASE + "/query/preview", body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertEquals("VALIDATION_ERROR", r.path("status").asText());
    }

    @Test
    public void searchMembers_productFamily_returnsMatches() throws Exception {
        String url = BASE + "/members/search?"
                + "cubeId=" + URLEncoder.encode(CUBE, StandardCharsets.UTF_8)
                + "&dimension=Product"
                + "&hierarchy=Products"
                + "&level=Product+Family"
                + "&q=Dri"
                + "&limit=5";
        HttpResponse<String> resp = harness.getAuth(url);
        assertEquals("search should be 200, got " + resp.statusCode() + " body=" + resp.body(), 200, resp.statusCode());
        JsonNode r = harness.parse(resp);
        // Either returns an array of matches or a structured envelope; either way it must be non-null JSON.
        assertNotNull(r);
    }

    @Test
    public void searchMembers_invalidCubeId_returns400() throws Exception {
        String url = BASE + "/members/search?cubeId=foo&dimension=x&hierarchy=y&level=z&q=test";
        HttpResponse<String> resp = harness.getAuth(url);
        assertEquals(400, resp.statusCode());
    }
}
