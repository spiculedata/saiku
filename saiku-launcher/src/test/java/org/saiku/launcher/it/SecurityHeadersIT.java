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
 * saiku#1165 / saiku#1917 — verifies the
 * {@link org.saiku.web.servlet.SecurityHeadersFilter} stamps the always-on browser
 * security headers on every response (incl. the {@code /ui/**} SPA surface whose
 * Spring Security chain is {@code security="none"}), that the SPA is NOT frameable
 * by a foreign origin out of the box (clickjacking hardening), and that non-SPA
 * responses enforce script-src while the shell relies on its own meta policy (saiku#2430).
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
    public void spaIsSameOriginFramableByDefault() throws Exception {
        // saiku#1917: /ui/ used to carry NO framing header at all (frameable by any
        // origin, clickjacking delete/share/admin). It must now be same-origin only.
        HttpResponse<String> resp = harness.getAnon("/ui/");
        assertEquals("nosniff still applies on /ui/", "nosniff", header(resp, "X-Content-Type-Options"));
        assertEquals("SAMEORIGIN", header(resp, "X-Frame-Options"));
        assertTrue(
                "frame-ancestors 'self' in the CSP, got: " + header(resp, "Content-Security-Policy"),
                header(resp, "Content-Security-Policy").contains("frame-ancestors 'self'"));
    }

    @Test
    public void spaShellCarriesNoHeaderScriptSrcAndNoReportOnlyPolicy() throws Exception {
        // saiku#2430: the shell's enforced policy is its <meta> tag; a header script-src
        // would block its inline bootstrap, and a default report-only header reported it.
        HttpResponse<String> resp = harness.getAnon("/ui/");
        String enforced = header(resp, "Content-Security-Policy");
        assertTrue("no header script-src on the shell, got: " + enforced, !enforced.contains("script-src"));
        assertEquals("", header(resp, "Content-Security-Policy-Report-Only"));
    }

    @Test
    public void nonSpaResponsesEnforceScriptSrcByDefault() throws Exception {
        HttpResponse<String> resp = harness.getAnon("/rest/saiku/info");
        String enforced = header(resp, "Content-Security-Policy");
        assertTrue(
                "enforced script-src 'self' outside the SPA, got: " + enforced, enforced.contains("script-src 'self'"));
        assertTrue(enforced.contains("object-src 'none'"));
    }

    private static String header(HttpResponse<String> resp, String name) {
        return resp.headers().firstValue(name).orElse("");
    }
}
