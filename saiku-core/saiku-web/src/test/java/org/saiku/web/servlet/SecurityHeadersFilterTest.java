/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for {@link SecurityHeadersFilter}'s frame-protection policy.
 *
 * <p>Frame protection is opt-in so the cross-origin {@code ?embed=1} feature
 * keeps working by default (a blanket {@code X-Frame-Options: DENY} broke it).
 */
public class SecurityHeadersFilterTest {

    private static final String PROP = "saiku.security.frameAncestors";

    @Test
    public void frameProtectionIsOffByDefault() {
        String prev = System.getProperty(PROP);
        try {
            System.clearProperty(PROP);
            assertNull(
                    "default must leave framing unrestricted so ?embed=1 works",
                    SecurityHeadersFilter.frameAncestors());
        } finally {
            restore(prev);
        }
    }

    @Test
    public void blankPropertyIsTreatedAsUnset() {
        String prev = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "   ");
            assertNull(SecurityHeadersFilter.frameAncestors());
        } finally {
            restore(prev);
        }
    }

    @Test
    public void frameAncestorsReadsAndTrimsProperty() {
        String prev = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "  'self' https://wiki.example.com  ");
            assertEquals("'self' https://wiki.example.com", SecurityHeadersFilter.frameAncestors());
        } finally {
            restore(prev);
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
    public void cspIsOffByDefaultAndReadsProperty() {
        String prev = System.getProperty("saiku.security.csp");
        try {
            System.clearProperty("saiku.security.csp");
            assertNull("no enforced CSP by default — SPA must not be broken", SecurityHeadersFilter.csp());
            System.setProperty("saiku.security.csp", "  default-src 'self'  ");
            assertEquals("default-src 'self'", SecurityHeadersFilter.csp());
        } finally {
            if (prev == null) {
                System.clearProperty("saiku.security.csp");
            } else {
                System.setProperty("saiku.security.csp", prev);
            }
        }
    }

    @Test
    public void cspReportOnlyIsOffByDefaultAndReadsProperty() {
        String prev = System.getProperty("saiku.security.cspReportOnly");
        try {
            System.clearProperty("saiku.security.cspReportOnly");
            assertNull(SecurityHeadersFilter.cspReportOnly());
            System.setProperty("saiku.security.cspReportOnly", "default-src 'self'");
            assertEquals("default-src 'self'", SecurityHeadersFilter.cspReportOnly());
        } finally {
            if (prev == null) {
                System.clearProperty("saiku.security.cspReportOnly");
            } else {
                System.setProperty("saiku.security.cspReportOnly", prev);
            }
        }
    }

    private static void restore(String prev) {
        if (prev == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, prev);
        }
    }
}
