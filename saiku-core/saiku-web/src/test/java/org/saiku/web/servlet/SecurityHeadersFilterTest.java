/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

    private static void restore(String name, String prev) {
        if (prev == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, prev);
        }
    }
}
