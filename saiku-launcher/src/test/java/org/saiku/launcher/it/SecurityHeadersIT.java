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
 * stamps browser-facing security headers on every response, including the
 * {@code /ui/**} SPA surface whose Spring Security chain is {@code security="none"}.
 */
public class SecurityHeadersIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void headersPresentOnRestSurface() throws Exception {
        HttpResponse<String> resp = harness.getAnon("/rest/saiku/info");
        assertEquals("X-Frame-Options", "DENY", header(resp, "X-Frame-Options"));
        assertEquals("X-Content-Type-Options", "nosniff", header(resp, "X-Content-Type-Options"));
        assertTrue(
                "CSP must forbid framing, was: " + header(resp, "Content-Security-Policy"),
                header(resp, "Content-Security-Policy").contains("frame-ancestors"));
        assertTrue("Referrer-Policy present", header(resp, "Referrer-Policy").length() > 0);
        assertTrue(
                "Permissions-Policy present", header(resp, "Permissions-Policy").length() > 0);
    }

    @Test
    public void headersPresentOnSpaSurface() throws Exception {
        // /ui/** is security="none" — the servlet filter is what protects it.
        // Headers are stamped regardless of the status code the SPA path returns.
        HttpResponse<String> resp = harness.getAnon("/ui/");
        assertEquals("X-Frame-Options on /ui/", "DENY", header(resp, "X-Frame-Options"));
        assertEquals("nosniff on /ui/", "nosniff", header(resp, "X-Content-Type-Options"));
    }

    private static String header(HttpResponse<String> resp, String name) {
        return resp.headers().firstValue(name).orElse("");
    }
}
