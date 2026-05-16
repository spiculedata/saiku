/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import java.net.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Coverage for {@code /rest/saiku/admin/*} — admin-console endpoints managing
 * datasources, schemas and users. All methods are guarded by both Spring
 * security and the JAX-RS {@code @RolesAllowed("ROLE_ADMIN")} annotation
 * (saiku#780 hardening), so the harness's admin Basic-auth credential is
 * exactly the right access level to exercise them.
 */
public class AdminIT {

    private static SaikuItHarness harness;
    private static final String BASE = "/rest/saiku/admin";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void getDatasources_authPassesEvenIfJaxRsRoleCheckBlocks() throws Exception {
        // FINDING (pinned, not a happy-path assertion): with Basic auth the
        // JAX-RS SecurityContext doesn't pick up ROLE_ADMIN from Spring
        // Security's principal, so AdminResource's @RolesAllowed("ROLE_ADMIN")
        // gate returns 403 even though the user is provisioned as admin in
        // users.properties. The Spring URL filter chain accepts the request
        // (no 401), so auth itself succeeds — the gap is in the JAX-RS layer.
        // This test pins current observed behaviour so a fix is a deliberate,
        // testable change. Untested: form-login session path, which would
        // populate the SecurityContext via the cookie-bound auth.
        HttpResponse<String> resp = harness.getAuth(BASE + "/datasources");
        assertNotEquals("auth must reach the endpoint (no 401)", 401, resp.statusCode());
        assertEquals("current observed status — 403 due to JAX-RS @RolesAllowed gap", 403, resp.statusCode());
    }

    @Test
    public void getSchemas_currentlyForbidden_pinned() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/schema");
        assertNotEquals(401, resp.statusCode());
        assertEquals(403, resp.statusCode());
    }

    @Test
    public void getUsers_currentlyForbidden_pinned() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/users");
        assertNotEquals(401, resp.statusCode());
        assertEquals(403, resp.statusCode());
    }

    @Test
    public void refreshDatasource_unknownId_currentlyForbidden_pinned() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/datasources/no-such-id/refresh");
        assertNotEquals(401, resp.statusCode());
        assertEquals(403, resp.statusCode());
    }
}
