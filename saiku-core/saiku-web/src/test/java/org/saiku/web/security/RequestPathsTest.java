/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** {@link RequestPaths#pathWithinApplication}: the one path every security filter decides on (#2342). */
public class RequestPathsTest {

    private static MockHttpServletRequest req(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }

    private static String path(String uri) {
        return RequestPaths.pathWithinApplication(req(uri));
    }

    @Test
    public void pathWithinApplication_canonicalPathIsUnchanged() {
        assertEquals("/rest/saiku/session", path("/rest/saiku/session"));
    }

    @Test
    public void pathWithinApplication_decodesPercentEncoding() {
        assertEquals("/rest/saiku/x", path("/%72est/saiku/x"));
        assertEquals("/rest/saiku/session", path("/rest/saiku/%73ession"));
    }

    @Test
    public void pathWithinApplication_decodesUtf8() {
        assertEquals("/rest/saiku/café", path("/rest/saiku/caf%C3%A9"));
    }

    @Test
    public void pathWithinApplication_decodesOnlyOnce() {
        assertEquals("/rest/saiku/%72x", path("/rest/saiku/%2572x"));
    }

    @Test
    public void pathWithinApplication_stripsContextPath() {
        MockHttpServletRequest r = req("/saiku/%72est/saiku/x");
        r.setContextPath("/saiku");
        assertEquals("/rest/saiku/x", RequestPaths.pathWithinApplication(r));
    }

    @Test
    public void pathWithinApplication_stripsPathParameters() {
        assertEquals("/rest/saiku/x", path("/rest/saiku/x;jsessionid=abc"));
        assertEquals("/rest/saiku/x", path("/rest;a=b/saiku;c=d/x"));
    }

    @Test
    public void pathWithinApplication_dropsDotSegmentsAndCollapsesSlashes() {
        assertEquals("/rest/saiku/x", path("/rest/./saiku/x"));
        assertEquals("/rest/saiku/x", path("/rest//saiku///x"));
        assertEquals("/rest/saiku/x", path("/%2e/rest/saiku/x"));
    }

    @Test
    public void pathWithinApplication_keepsTrailingSlash() {
        assertEquals("/rest/saiku/api/embed/", path("/rest/saiku/api/embed/"));
    }

    @Test
    public void pathWithinApplication_rejectsParentSegments() {
        assertNull(path("/rest/saiku/../admin"));
        assertNull(path("/rest/saiku/%2e%2e/admin"));
        assertNull(path("/rest/saiku/%2E./admin"));
    }

    @Test
    public void pathWithinApplication_rejectsEncodedSeparatorsAndControlCharacters() {
        assertNull(path("/rest/saiku%2fapi"));
        assertNull(path("/rest/saiku%2Fapi"));
        assertNull(path("/rest/saiku%5capi"));
        assertNull(path("/rest/saiku\\api"));
        assertNull(path("/rest/saiku/x%00"));
    }

    @Test
    public void pathWithinApplication_rejectsMalformedEncoding() {
        assertNull(path("/rest/saiku/%zz"));
        assertNull(path("/rest/saiku/%7"));
        assertNull(path("/rest/saiku/%ff"));
    }

    @Test
    public void pathWithinApplication_emptyAndRootBecomeRoot() {
        assertEquals("/", path(""));
        assertEquals("/", path("/"));
    }
}
