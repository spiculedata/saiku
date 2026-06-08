/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.svg;

import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * saiku#1165 — SVG export must reject anything that would make Batik fetch an
 * external resource (SSRF / local-file-read) or expand external entities (XXE).
 */
public class SvgSecurityTest {

    private static void rejected(String svg) {
        try {
            SvgSecurity.requireSafe(svg);
            fail("expected rejection for: " + svg);
        } catch (IllegalArgumentException expected) {
            // good
        }
    }

    @Test
    public void rejectsDoctype() {
        rejected("<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg/>");
    }

    @Test
    public void rejectsHttpImageHref() {
        rejected(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><image xlink:href=\"http://169.254.169.254/latest/meta-data/\"/></svg>");
    }

    @Test
    public void rejectsFileHref() {
        rejected("<svg><image href=\"file:///etc/passwd\"/></svg>");
    }

    @Test
    public void rejectsHttpsAndCssUrl() {
        rejected("<svg><image href=\"https://evil.example.com/x.png\"/></svg>");
        rejected("<svg><rect style=\"fill:url('http://evil/x')\"/></svg>");
    }

    @Test
    public void rejectsBareAbsolutePathHref() {
        rejected("<svg><image href=\"/etc/passwd\"/></svg>");
    }

    @Test
    public void allowsSelfContainedSvg() {
        // No refs at all — the normal ECharts chart export.
        SvgSecurity.requireSafe("<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0 L10 10\"/></svg>");
        // Inline data: image and same-document fragment refs are safe.
        SvgSecurity.requireSafe("<svg><image href=\"data:image/png;base64,AAAA\"/><use xlink:href=\"#g1\"/></svg>");
        // null is a no-op (blank-svg paths skip transcoding anyway).
        SvgSecurity.requireSafe(null);
    }
}
