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
 * Smoke test: the launcher comes up, the {@code /rest/saiku/info} endpoint
 * answers, and the response body is a JSON list. This is the gating IT — if
 * this is red, the rest of the IT surface is meaningless.
 */
public class InfoEndpointIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void info_returns200WithJsonArray() throws Exception {
        HttpResponse<String> resp = harness.getAuth("/rest/saiku/info");
        assertEquals("info should be 200, body was: " + resp.body(), 200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue("info body must be a JSON array (plugin list), got: " + resp.body(), body.isArray());
    }

    @Test
    public void info_unauthenticatedAlsoAllowed() throws Exception {
        // The /info endpoint is configured anonymous-allowed in the shipped
        // applicationContext-spring-security-memory.xml — the SPA fetches it
        // pre-login to render the plugin list. Lock that contract here so a
        // tightening change is an explicit decision, not a silent regression.
        HttpResponse<String> resp = harness.getAnon("/rest/saiku/info");
        assertEquals(200, resp.statusCode());
    }
}
