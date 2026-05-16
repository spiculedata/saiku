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
 * Coverage for {@code /rest/saiku/statistics/mondrian/*} — Mondrian-server
 * introspection used by the admin console / Grafana scrape targets.
 */
public class StatisticsIT {

    private static SaikuItHarness harness;
    private static final String BASE = "/rest/saiku/statistics/mondrian";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void getMondrianStats_returns200WithMondrianBody() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE);
        assertEquals(200, resp.statusCode());
        // Body shape is MondrianStats; just confirm we got JSON.
        JsonNode body = harness.parse(resp);
        assertNotNull(body);
    }

    @Test
    public void getMondrianServer_returns200() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/server");
        assertEquals(200, resp.statusCode());
        assertNotNull(harness.parse(resp));
    }

    @Test
    public void getMondrianServerVersion_returns200WithVersionString() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/server/version");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        // MondrianVersion has versionString, productName, etc.
        assertTrue("version body must expose versionString", body.has("versionString"));
    }
}
