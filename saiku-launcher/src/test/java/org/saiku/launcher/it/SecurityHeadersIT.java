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
 * by a foreign origin out of the box (clickjacking hardening), and that the
 * documented CSP ships in report-only form so it blocks nothing until validated.
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
    public void spaShipsTheCspInReportOnlyFormByDefault() throws Exception {
        // Nothing is blocked until an operator sets -Dsaiku.security.csp, so a
        // deployment can browser-validate the policy first.
        HttpResponse<String> resp = harness.getAnon("/ui/");
        String enforced = header(resp, "Content-Security-Policy");
        assertTrue("no enforced script-src by default, got: " + enforced, !enforced.contains("script-src"));
        String reportOnly = header(resp, "Content-Security-Policy-Report-Only");
        assertTrue("report-only CSP shipped by default", reportOnly.contains("script-src 'self'"));
        assertTrue(reportOnly.contains("object-src 'none'"));
    }

    private static String header(HttpResponse<String> resp, String name) {
        return resp.headers().firstValue(name).orElse("");
    }
}
