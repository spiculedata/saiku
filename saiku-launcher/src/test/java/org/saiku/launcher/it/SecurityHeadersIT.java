/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#1165 — verifies the {@link org.saiku.web.servlet.SecurityHeadersFilter}
 * stamps the always-on browser security headers on every response (incl. the
 * {@code /ui/**} SPA surface whose Spring Security chain is {@code security="none"}),
 * AND that frame protection is OFF by default so the cross-origin {@code ?embed=1}
 * embed feature keeps working (a blanket {@code X-Frame-Options: DENY} broke it).
 */
public class SecurityHeadersIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void alwaysOnHeadersPresentOnRestSurface() throws Exception {
        HttpResponse<String> resp = harness.getAnon("/rest/saiku/info");
        assertEquals("X-Content-Type-Options", "nosniff", header(resp, "X-Content-Type-Options"));
        assertTrue("Referrer-Policy present", header(resp, "Referrer-Policy").length() > 0);
        assertTrue(
                "Permissions-Policy present", header(resp, "Permissions-Policy").length() > 0);
    }

    @Test
    public void embedSurfaceStaysFramableByDefault() throws Exception {
        // /ui/ is what ?embed=1 frames into a wiki/Confluence/Notion page. By
        // default (no saiku.security.frameAncestors) there must be NO framing
        // header, or the iframe-embed feature breaks. nosniff still applies.
        HttpResponse<String> resp = harness.getAnon("/ui/");
        assertEquals("nosniff still applies on /ui/", "nosniff", header(resp, "X-Content-Type-Options"));
        assertEquals("embed must not be blocked by default — no X-Frame-Options", "", header(resp, "X-Frame-Options"));
        assertTrue(
                "embed must not be blocked by default — no frame-ancestors CSP",
                !header(resp, "Content-Security-Policy").contains("frame-ancestors"));
    }

    private static String header(HttpResponse<String> resp, String name) {
        return resp.headers().firstValue(name).orElse("");
    }
}
