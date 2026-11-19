/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link SecurityHeadersFilter}'s CSP / frame-protection policy.
 *
 * <p>Since saiku#1917 frame protection defaults ON ({@code frame-ancestors 'self'} +
 * {@code X-Frame-Options: SAMEORIGIN}) and the documented policy ships as
 * {@code Content-Security-Policy-Report-Only}, so the SPA is neither clickjackable
 * nor unconstrained. The cross-origin {@code ?embed=1} iframe feature is reached
 * with an allow-list property (or {@code off} to fully opt out).
 */
public class SecurityHeadersFilterTest {

    private static final String PROP = "saiku.security.frameAncestors";
    private static final String RO_PROP = "saiku.security.cspReportOnly";

    @Test
    public void frameProtectionDefaultsToSelf() {
        String prev = System.getProperty(PROP);
        try {
            System.clearProperty(PROP);
            assertEquals(
                    "saiku#1917: the SPA must be same-origin-framable out of the box",
                    "'self'",
                    SecurityHeadersFilter.frameAncestors());
            assertEquals("SAMEORIGIN", SecurityHeadersFilter.xFrameOptionsFor(SecurityHeadersFilter.frameAncestors()));
        } finally {
            restore(PROP, prev);
        }
    }

    @Test
    public void blankPropertyFallsBackToTheDefault() {
        String prev = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "   ");
            assertEquals("'self'", SecurityHeadersFilter.frameAncestors());
        } finally {
            restore(PROP, prev);
        }
    }

    @Test
    public void offPropertyDisablesFramingHeadersEntirely() {
        String prev = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "off");
            assertNull("pre-#1917 escape hatch: no framing headers at all", SecurityHeadersFilter.frameAncestors());
            System.setProperty(PROP, "*");
            assertNull(SecurityHeadersFilter.frameAncestors());
        } finally {
            restore(PROP, prev);
        }
    }

    @Test
    public void frameAncestorsReadsAndTrimsProperty() {
        String prev = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "  'self' https://wiki.example.com  ");
            assertEquals("'self' https://wiki.example.com", SecurityHeadersFilter.frameAncestors());
        } finally {
            restore(PROP, prev);
        }
    }

    @Test
    public void xFrameOptionsMapsNoneAndSelfButNotAllowLists() {
        assertEquals("DENY", SecurityHeadersFilter.xFrameOptionsFor("'none'"));
        assertEquals("SAMEORIGIN", SecurityHeadersFilter.xFrameOptionsFor("'self'"));
        // An allow-list cannot be expressed as X-Frame-Options → CSP frame-ancestors only.
        assertNull(SecurityHeadersFilter.xFrameOptionsFor("'self' https://wiki.example.com"));
    }

    @Test
    public void cspIsNotEnforcedByDefaultAndReadsProperty() {
        String prev = System.getProperty("saiku.security.csp");
        try {
            System.clearProperty("saiku.security.csp");
            assertNull("no enforced CSP by default — SPA must not be broken", SecurityHeadersFilter.csp());
            System.setProperty("saiku.security.csp", "  default-src 'self'  ");
            assertEquals("default-src 'self'", SecurityHeadersFilter.csp());
        } finally {
            restore("saiku.security.csp", prev);
        }
    }

    @Test
    public void cspReportOnlyShipsTheDocumentedPolicyByDefault() {
        String prev = System.getProperty(RO_PROP);
        try {
            System.clearProperty(RO_PROP);
            String ro = SecurityHeadersFilter.cspReportOnly();
            assertEquals(SecurityHeadersFilter.DEFAULT_CSP, ro);
            // The directives that matter for the reported XSS findings.
            assertTrue(ro.contains("script-src 'self'"));
            assertTrue(ro.contains("object-src 'none'"));
            assertTrue(ro.contains("base-uri 'self'"));
            // Still permissive enough for the SPA's own machinery.
            assertTrue("monaco uses blob: workers", ro.contains("worker-src 'self' blob:"));
            assertTrue("sveltekit injects style tags", ro.contains("style-src 'self' 'unsafe-inline'"));
        } finally {
            restore(RO_PROP, prev);
        }
    }

    @Test
    public void cspReportOnlyReadsPropertyAndHonoursOff() {
        String prev = System.getProperty(RO_PROP);
        try {
            System.setProperty(RO_PROP, "default-src 'self'");
            assertEquals("default-src 'self'", SecurityHeadersFilter.cspReportOnly());
            System.setProperty(RO_PROP, "off");
            assertNull("operator may silence the report-only header", SecurityHeadersFilter.cspReportOnly());
            System.setProperty(RO_PROP, "  ");
            assertEquals(
                    "blank is unset, so the default policy applies",
                    SecurityHeadersFilter.DEFAULT_CSP,
                    SecurityHeadersFilter.cspReportOnly());
        } finally {
            restore(RO_PROP, prev);
        }
    }

    @Test
    public void enforcedCspGetsFrameAncestorsAppendedWhenItOmitsThem() {
        assertEquals(
                "default-src 'self'; frame-ancestors 'self'",
                SecurityHeadersFilter.withFrameAncestors("default-src 'self'", "'self'"));
        assertEquals(
                "'self' https://wiki.example.com allowed in the enforced policy too",
                "default-src 'self'; frame-ancestors 'self' https://wiki.example.com",
                SecurityHeadersFilter.withFrameAncestors("default-src 'self'", "'self' https://wiki.example.com"));
    }

    @Test
    public void enforcedCspIsLeftAloneWhenItAlreadyDeclaresFrameAncestors() {
        assertEquals(
                "default-src 'self'; frame-ancestors 'none'",
                SecurityHeadersFilter.withFrameAncestors("default-src 'self'; frame-ancestors 'none'", "'self'"));
        // "frame-ancestors" must match as a directive name, not as a substring of
        // some other token — an unknown directive named frame-ancestors-x must
        // still get the real directive appended.
        assertEquals(
                "default-src 'self'; frame-ancestors-x 'none'; frame-ancestors 'self'",
                SecurityHeadersFilter.withFrameAncestors("default-src 'self'; frame-ancestors-x 'none'", "'self'"));
    }

    @Test
    public void enforcedCspIsUntouchedWhenFramingIsOptedOut() {
        assertEquals("default-src 'self'", SecurityHeadersFilter.withFrameAncestors("default-src 'self'", null));
    }

    // ---- saiku#2440: the dashboard plugin frame carries its own sandboxed policy ----

    private static MockHttpServletResponse serve(String contextPath, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setContextPath(contextPath);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new SecurityHeadersFilter().doFilter(req, resp, chain);
        assertTrue("the request must still reach the servlet", chain.getRequest() == req);
        return resp;
    }

    private static Map<String, String> directives(String csp) {
        Map<String, String> out = new LinkedHashMap<>();
        Arrays.stream(csp.split(";"))
                .map(String::trim)
                .filter(d -> !d.isEmpty())
                .forEach(d -> {
                    String[] parts = d.split("\\s+", 2);
                    out.put(parts[0], parts.length > 1 ? parts[1] : "");
                });
        return out;
    }

    @Test
    public void pluginFrameIsServedWithItsOwnSandboxedPolicy() throws Exception {
        String prevFa = System.getProperty(PROP);
        String prevRo = System.getProperty(RO_PROP);
        String prevCsp = System.getProperty("saiku.security.csp");
        try {
            System.clearProperty(PROP);
            System.clearProperty(RO_PROP);
            // An operator's shell policy must not replace the frame's: it would block the plugin.
            System.setProperty("saiku.security.csp", "default-src 'self'");
            MockHttpServletResponse resp = serve("", "/ui/plugin-frame.html");
            Map<String, String> d = directives(resp.getHeader("Content-Security-Policy"));
            assertEquals("allow-scripts", d.get("sandbox"));
            assertEquals("'none'", d.get("default-src"));
            assertEquals("'unsafe-inline'", d.get("script-src"));
            assertEquals("'unsafe-inline'", d.get("style-src"));
            assertEquals("data:", d.get("img-src"));
            assertEquals("'none'", d.get("connect-src"));
            assertEquals("'none'", d.get("base-uri"));
            assertEquals("'none'", d.get("form-action"));
            assertEquals("'self'", d.get("frame-ancestors"));
            assertFalse(resp.getHeader("Content-Security-Policy").contains("allow-same-origin"));
            assertNull(
                    "no report-only shell policy on the frame", resp.getHeader("Content-Security-Policy-Report-Only"));
            assertEquals("SAMEORIGIN", resp.getHeader("X-Frame-Options"));
            assertEquals("nosniff", resp.getHeader("X-Content-Type-Options"));
        } finally {
            restore(PROP, prevFa);
            restore(RO_PROP, prevRo);
            restore("saiku.security.csp", prevCsp);
        }
    }

    @Test
    public void pluginFrameFollowsTheConfiguredFrameAncestors() throws Exception {
        String prev = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "'self' https://wiki.example.com");
            assertEquals(
                    "'self' https://wiki.example.com",
                    directives(serve("", "/ui/plugin-frame.html").getHeader("Content-Security-Policy"))
                            .get("frame-ancestors"));
            System.setProperty(PROP, "off");
            String csp = serve("", "/ui/plugin-frame.html").getHeader("Content-Security-Policy");
            assertEquals(SecurityHeadersFilter.PLUGIN_FRAME_CSP, csp);
        } finally {
            restore(PROP, prev);
        }
    }

    @Test
    public void pluginFramePathIsMatchedExactlyUnderTheContextPath() throws Exception {
        assertTrue(directives(serve("/saiku", "/saiku/ui/plugin-frame.html").getHeader("Content-Security-Policy"))
                .containsKey("sandbox"));
        for (String uri : new String[] {"/ui/", "/ui/index.html", "/ui/plugin-frame.html.js", "/plugin-frame.html"}) {
            MockHttpServletResponse resp = serve("", uri);
            String csp = resp.getHeader("Content-Security-Policy");
            assertFalse(uri + " must keep the shell policy", csp != null && csp.contains("sandbox"));
            assertFalse(uri + " must not allow inline script", csp != null && csp.contains("unsafe-inline"));
        }
    }

    private static void restore(String name, String prev) {
        if (prev == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, prev);
        }
    }
}
