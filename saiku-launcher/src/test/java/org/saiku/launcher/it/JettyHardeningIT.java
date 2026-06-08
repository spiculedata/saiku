/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertFalse;

import java.net.http.HttpResponse;
import java.util.Locale;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#1165 audit-3 — verifies the embedded Jetty transport is hardened:
 * the {@code Server} response banner must NOT disclose the Jetty version
 * (sendServerVersion=false on the connector's HttpConfiguration).
 *
 * <p>Kept deliberately minimal: any reachable path is enough to assert the
 * connector-level header suppression, since the banner is stamped by the
 * connector, not by the resource.
 */
public class JettyHardeningIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void serverBannerDoesNotDiscloseJetty() throws Exception {
        HttpResponse<String> resp = harness.getAnon("/rest/saiku/info");
        String server = resp.headers().firstValue("Server").orElse("");
        assertFalse(
                "Server header must not disclose Jetty (was: '" + server + "')",
                server.toLowerCase(Locale.ROOT).contains("jetty"));
    }

    @Test
    public void noXPoweredByHeader() throws Exception {
        HttpResponse<String> resp = harness.getAnon("/ui/");
        String xpb = resp.headers().firstValue("X-Powered-By").orElse("");
        assertFalse("X-Powered-By must be suppressed (was: '" + xpb + "')", xpb.length() > 0);
    }
}
