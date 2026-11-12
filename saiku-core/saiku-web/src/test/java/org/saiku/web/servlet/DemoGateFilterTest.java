/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.Assert.*;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Path-classification logic for the demo gate — the security-critical part. */
public class DemoGateFilterTest {

    @Test
    public void infoEndpointsAreAllowlisted() {
        assertFalse(DemoGateFilter.requiresVerification("/rest/saiku/info"));
        assertFalse(DemoGateFilter.requiresVerification("/rest/saiku/info/capabilities"));
    }

    @Test
    public void gateEndpointsAreAllowlisted() {
        assertFalse(DemoGateFilter.requiresVerification("/rest/saiku/demo/gate"));
        assertFalse(DemoGateFilter.requiresVerification("/rest/saiku/demo/gate/request"));
        assertFalse(DemoGateFilter.requiresVerification("/rest/saiku/demo/gate/verify"));
        assertFalse(DemoGateFilter.requiresVerification("/rest/saiku/demo/gate/status"));
    }

    @Test
    public void loginEndpointRequiresVerification() {
        // The whole point: the cookie gates the login form.
        assertTrue(DemoGateFilter.requiresVerification("/rest/saiku/session"));
    }

    @Test
    public void apiAndAdminEndpointsRequireVerification() {
        assertTrue(DemoGateFilter.requiresVerification("/rest/saiku/api/ai/cubes"));
        assertTrue(DemoGateFilter.requiresVerification("/rest/saiku/admin/discover"));
        assertTrue(DemoGateFilter.requiresVerification("/rest/saiku/query/abc"));
    }

    @Test
    public void nonSaikuPathsAreNotGated() {
        assertFalse(DemoGateFilter.requiresVerification("/ui/index.html"));
        assertFalse(DemoGateFilter.requiresVerification("/rest/other/thing"));
    }

    @Test
    public void nullPathFailsClosed() {
        // null = the path could not be normalised (#2342); never wave it through.
        assertTrue(DemoGateFilter.requiresVerification((String) null));
    }

    @Test
    public void percentEncodedPrefix_isStillGated() {
        assertTrue(DemoGateFilter.requiresVerification(new MockHttpServletRequest("POST", "/%72est/saiku/session")));
        assertTrue(DemoGateFilter.requiresVerification(new MockHttpServletRequest("GET", "/rest//saiku/./session")));
    }

    @Test
    public void percentEncodedAllowlistEntry_isStillAllowlisted() {
        assertFalse(DemoGateFilter.requiresVerification(new MockHttpServletRequest("GET", "/%72est/saiku/info")));
    }

    @Test
    public void nonNormalisableRequestPath_isGated() {
        assertTrue(
                DemoGateFilter.requiresVerification(new MockHttpServletRequest("GET", "/rest/saiku/info/../session")));
    }

    @Test
    public void allowlistPrefixDoesNotOvermatch() {
        // "/information" must not be treated as the "/info" allowlist entry.
        assertTrue(DemoGateFilter.requiresVerification("/rest/saiku/information"));
    }
}
