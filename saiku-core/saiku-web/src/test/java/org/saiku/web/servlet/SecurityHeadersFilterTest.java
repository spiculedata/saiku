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
 * {@code X-Frame-Options: SAMEORIGIN}); since saiku#2430 non-SPA responses also enforce
 * {@code script-src 'self'} and there is no default report-only policy. The cross-origin {@code ?embed=1} iframe feature is reached
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
    public void noReportOnlyPolicyIsShippedByDefault() throws Exception {
        // saiku#2430: a default report-only policy reported the inline SvelteKit bootstrap
        // on every page load; the shell's enforced <meta> policy replaces it.
        String prev = System.getProperty(RO_PROP);
        try {
            System.clearProperty(RO_PROP);
            assertNull(SecurityHeadersFilter.cspReportOnly());
            assertNull(serve("", "/ui/").getHeader("Content-Security-Policy-Report-Only"));
            assertNull(serve("", "/rest/saiku/info").getHeader("Content-Security-Policy-Report-Only"));
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
            assertNull("blank is unset", SecurityHeadersFilter.cspReportOnly());
        } finally {
            restore(RO_PROP, prev);
        }
    }

    @Test
    public void nonSpaResponsesEnforceScriptSrcByDefault() throws Exception {
        String prev = System.getProperty("saiku.security.csp");
        try {
            System.clearProperty("saiku.security.csp");
            for (String uri : new String[] {"/rest/saiku/info", "/login.html", "/some/error", "/uix/index.html"}) {
                Map<String, String> d = directives(serve("", uri).getHeader("Content-Security-Policy"));
                assertEquals(uri, "'none'", d.get("default-src"));
                assertEquals(uri, "'self'", d.get("script-src"));
                assertEquals(uri, "'none'", d.get("object-src"));
                assertEquals(uri, "'self'", d.get("base-uri"));
                assertEquals(uri, "'self'", d.get("frame-ancestors"));
                // saiku#2528: an injected form cannot post credentials off-origin, and a
                // rendered cell image property cannot leak values to an external host.
                assertEquals(uri, "'self'", d.get("form-action"));
                assertEquals(uri, "'self' data:", d.get("img-src"));
                assertEquals(uri, "'unsafe-inline'", d.get("style-src"));
            }
            assertEquals(
                    "/saiku context path",
                    "'self'",
                    directives(serve("/saiku", "/saiku/rest/saiku/info").getHeader("Content-Security-Policy"))
                            .get("script-src"));
        } finally {
            restore("saiku.security.csp", prev);
        }
    }

    @Test
    public void nonSpaCspIsSealedAgainstExternalHosts() {
        // saiku#2528: the export surface renders query-author-chosen properties, so the
        // default policy must deny what it does not name and must not admit arbitrary hosts.
        Map<String, String> d = directives(SecurityHeadersFilter.NON_SPA_CSP);
        assertEquals("'none'", d.get("default-src"));
        assertEquals("'self'", d.get("form-action"));
        assertEquals("'self' data:", d.get("img-src"));
        assertFalse(
                "img-src must not allow arbitrary hosts",
                d.get("img-src").contains("*") || d.get("img-src").contains("http:"));
    }

    @Test
    public void spaShellAndDemoPagesGetNoHeaderScriptSrc() throws Exception {
        // Their inline bootstrap is pinned by hash in a <meta> policy; a header script-src
        // would intersect with it and block the bootstrap.
        String prev = System.getProperty("saiku.security.csp");
        try {
            System.clearProperty("saiku.security.csp");
            for (String uri : new String[] {
                "/",
                "/ui",
                "/ui/",
                "/ui/index.html",
                "/ui/dashboards/homes/admin/a.saikudash",
                "/ui/%64ashboards/x",
                "/aqvira-demo/index.html",
                "/embed-demo/"
            }) {
                assertEquals(uri, "frame-ancestors 'self'", serve("", uri).getHeader("Content-Security-Policy"));
            }
            assertEquals(
                    "frame-ancestors 'self'",
                    serve("/saiku", "/saiku/ui/index.html").getHeader("Content-Security-Policy"));
        } finally {
            restore("saiku.security.csp", prev);
        }
    }

    @Test
    public void operatorCspStillWinsOnEveryResponse() throws Exception {
        String prev = System.getProperty("saiku.security.csp");
        try {
            System.setProperty("saiku.security.csp", "default-src 'self'");
            assertEquals(
                    "default-src 'self'; frame-ancestors 'self'",
                    serve("", "/rest/saiku/info").getHeader("Content-Security-Policy"));
            assertEquals(
                    "default-src 'self'; frame-ancestors 'self'",
                    serve("", "/ui/").getHeader("Content-Security-Policy"));
        } finally {
            restore("saiku.security.csp", prev);
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
    public void pluginFrameAlwaysAdmitsSelfButTheShellKeepsTheOperatorValue() throws Exception {
        // The frame's parent is always the SPA, so 'none' or an allow-list without 'self' would
        // leave every plugin tile empty. The shell itself keeps exactly the configured value.
        String prev = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "'none'");
            MockHttpServletResponse frame = serve("", "/ui/plugin-frame.html");
            assertEquals(
                    "'self'",
                    directives(frame.getHeader("Content-Security-Policy")).get("frame-ancestors"));
            assertEquals("SAMEORIGIN", frame.getHeader("X-Frame-Options"));
            MockHttpServletResponse shell = serve("", "/ui/");
            assertEquals("frame-ancestors 'none'", shell.getHeader("Content-Security-Policy"));
            assertEquals("DENY", shell.getHeader("X-Frame-Options"));

            System.setProperty(PROP, "https://wiki.example.com");
            frame = serve("", "/ui/plugin-frame.html");
            assertEquals(
                    "'self' https://wiki.example.com",
                    directives(frame.getHeader("Content-Security-Policy")).get("frame-ancestors"));
            assertNull("an allow-list has no X-Frame-Options form", frame.getHeader("X-Frame-Options"));
            shell = serve("", "/ui/");
            assertEquals("frame-ancestors https://wiki.example.com", shell.getHeader("Content-Security-Policy"));
        } finally {
            restore(PROP, prev);
        }
    }

    @Test
    public void pluginFrameAncestorsDerivation() {
        assertEquals("'self'", SecurityHeadersFilter.pluginFrameAncestors("'self'"));
        assertEquals("'self'", SecurityHeadersFilter.pluginFrameAncestors("'none'"));
        assertEquals("'self'", SecurityHeadersFilter.pluginFrameAncestors("'NONE'"));
        assertEquals("'self' https://a.example", SecurityHeadersFilter.pluginFrameAncestors("https://a.example"));
        assertEquals(
                "https://a.example 'self'", SecurityHeadersFilter.pluginFrameAncestors("https://a.example 'self'"));
        assertNull(SecurityHeadersFilter.pluginFrameAncestors(null));
    }

    @Test
    public void pluginFramePolicyAppliesToEveryEncodedSpellingOfThePath() throws Exception {
        // The container decodes before it picks the file (#2342), so these all serve the
        // bootstrap. Each must carry the sandboxed frame policy, not the shell's.
        String[][] cases = {
            {"", "/ui/%70lugin-frame.html"},
            {"", "/ui/plugin%2Dframe.html"},
            {"", "/%75i/plugin-frame.html"},
            {"", "/ui/plugin-frame.html;jsessionid=x"},
            {"", "/ui/./plugin-frame.html"},
            {"", "//ui//plugin-frame.html"},
            {"/saiku", "/saiku/ui/%70lugin-frame.html"},
            {"/saiku", "/%73aiku/ui/plugin-frame.html"}
        };
        for (String[] c : cases) {
            MockHttpServletResponse resp = serve(c[0], c[1]);
            Map<String, String> d = directives(resp.getHeader("Content-Security-Policy"));
            assertEquals(c[1] + " must be sandboxed", "allow-scripts", d.get("sandbox"));
            assertEquals(c[1], "'none'", d.get("default-src"));
            assertNull(c[1], resp.getHeader("Content-Security-Policy-Report-Only"));
        }
    }

    @Test
    public void pluginFramePathIsMatchedExactlyUnderTheContextPath() throws Exception {
        assertTrue(directives(serve("/saiku", "/saiku/ui/plugin-frame.html").getHeader("Content-Security-Policy"))
                .containsKey("sandbox"));
        for (String uri : new String[] {
            "/ui/",
            "/ui/index.html",
            "/ui/plugin-frame.html.js",
            "/plugin-frame.html",
            "/ui/x/plugin-frame.html",
            "/ui/plugin-frame.htm",
            "/ui/../ui/plugin-frame.html",
            "/ui/plugin-frame.html/"
        }) {
            MockHttpServletResponse resp = serve("", uri);
            String csp = resp.getHeader("Content-Security-Policy");
            assertFalse(uri + " must keep the shell policy", csp != null && csp.contains("sandbox"));
            // saiku#2528: the non-SPA policy allows inline *styles* (the export page inlines
            // its stylesheet), so the no-inline-script guarantee is asserted on script-src.
            String scriptSrc = directives(csp).get("script-src");
            assertTrue(uri + " must not allow inline script", scriptSrc == null || scriptSrc.equals("'self'"));
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
