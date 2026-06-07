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

    private static void restore(String prev) {
        if (prev == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, prev);
        }
    }
}
