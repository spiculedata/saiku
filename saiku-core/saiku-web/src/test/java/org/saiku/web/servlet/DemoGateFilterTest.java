/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.Assert.*;

import org.junit.Test;

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
    public void nonSaikuAndNullPathsAreNotGated() {
        assertFalse(DemoGateFilter.requiresVerification("/ui/index.html"));
        assertFalse(DemoGateFilter.requiresVerification("/rest/other/thing"));
        assertFalse(DemoGateFilter.requiresVerification(null));
    }

    @Test
    public void allowlistPrefixDoesNotOvermatch() {
        // "/information" must not be treated as the "/info" allowlist entry.
        assertTrue(DemoGateFilter.requiresVerification("/rest/saiku/information"));
    }
}
