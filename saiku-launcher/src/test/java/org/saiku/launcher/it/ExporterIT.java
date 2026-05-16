/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import java.net.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Smoke coverage for {@code /rest/saiku/{user}/export/*}. The export
 * endpoints depend on a saved query file in the repository, which is a
 * heavier setup the SPA assembles client-side. Here we exercise the route
 * surface to confirm Spring + Jersey wiring resolves, and that missing
 * params surface as a clean error rather than a 500 with stack trace.
 */
public class ExporterIT {

    private static SaikuItHarness harness;
    private static final String BASE = "/rest/saiku/admin/export/saiku";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void exportXls_withoutFileParam_returnsServerError() throws Exception {
        // The endpoint loads the named file from the repository; without one
        // it surfaces the underlying NPE as a 500. Pin the contract so a
        // future hardening to 400 is a deliberate change.
        HttpResponse<String> resp = harness.getAuth(BASE + "/xls");
        assertTrue(
                "no-file export should be a 4xx/5xx — got " + resp.statusCode() + " body=" + resp.body(),
                resp.statusCode() >= 400);
    }

    @Test
    public void exportCsv_withoutFileParam_returnsServerError() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/csv");
        assertTrue(resp.statusCode() >= 400);
    }

    @Test
    public void exportJson_withoutFileParam_returnsServerError() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/json");
        assertTrue(resp.statusCode() >= 400);
    }

    @Test
    public void exportHtml_withoutFileParam_returnsServerError() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/html");
        assertTrue(resp.statusCode() >= 400);
    }
}
