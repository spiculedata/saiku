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
 * Coverage for {@code /rest/saiku/admin/*} — admin-console endpoints managing datasources,
 * schemas and users.
 *
 * <p>The admin boundary is held by a Spring URL gate, not the JAX-RS layer:
 * {@code <intercept-url pattern="/rest/saiku/admin/**" access="hasRole('ADMIN')"/>} in
 * {@code applicationContext-saiku.xml}, evaluated BEFORE the generic
 * {@code /rest/** isFullyAuthenticated()} rule. That gate is platform-independent, so the
 * boundary assertions below (non-admin → 403, anonymous → 401) hold on every runtime. The
 * admin's own happy-path access is a plain 200.
 *
 * <p>Historical note: an earlier revision of this IT pinned the admin GETs at 403 and blamed a
 * JAX-RS {@code @RolesAllowed} "gap". That 403 was a platform-VARIABLE artifact of the JAX-RS
 * {@code SecurityContext} role mapping (Linux CI resolved ROLE_ADMIN and returned 200; Windows
 * did not and returned 403) — a reliability wart in a redundant layer, never an exposure, because
 * the URL gate already denies non-admins first. The pins now match the shipped runtime and the
 * (Linux-only) CI: admin → 200.
 */
public class AdminIT {

    private static SaikuItHarness harness;
    private static final String BASE = "/rest/saiku/admin";

    // saiku#1732: the harness seeds this non-admin user (ROLE_USER only, password "admin")
    // alongside the default admin. See SaikuItHarness.seedUsersFile.
    private static final String VIEWER_USER = "viewer";
    private static final String VIEWER_PASS = "admin";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    // ---- admin happy path: the admin's own access is a plain 200 ----

    @Test
    public void getDatasources_asAdmin_ok() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/datasources");
        assertEquals("admin must be allowed to list datasources, body=" + resp.body(), 200, resp.statusCode());
    }

    @Test
    public void getSchemas_asAdmin_ok() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/schema");
        assertEquals("admin must be allowed to list schemas, body=" + resp.body(), 200, resp.statusCode());
    }

    @Test
    public void getUsers_asAdmin_ok() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/users");
        assertEquals("admin must be allowed to list users, body=" + resp.body(), 200, resp.statusCode());
    }

    @Test
    public void refreshDatasource_unknownId_asAdmin_reachesHandler() throws Exception {
        // The admin is past the role gate, so an unknown id now reaches the handler, which fails
        // (no such datasource) → 500. The value here is proving the request is NOT bounced at the
        // boundary: a 401/403 would mean the admin can't even reach the handler.
        HttpResponse<String> resp = harness.getAuth(BASE + "/datasources/no-such-id/refresh");
        assertEquals(
                "admin must reach the refresh handler (unknown id → 500, not a boundary bounce), body=" + resp.body(),
                500,
                resp.statusCode());
    }

    // ---- admin BOUNDARY: this is the real coverage (saiku#1732) ----

    @Test
    public void nonAdmin_isForbiddenOnAdminUsers_403() throws Exception {
        // A ROLE_USER-only credential is authenticated (not 401) but denied by the URL gate
        // `/rest/saiku/admin/** hasRole('ADMIN')` — a 403, not a 401. Revert that intercept-url
        // (or drop ROLE_ADMIN from the pattern) and this flips to 200 → the test goes red.
        HttpResponse<String> resp = harness.getAuth(VIEWER_USER, VIEWER_PASS, BASE + "/users");
        assertEquals(
                "non-admin must be FORBIDDEN (not merely unauthenticated) on /admin/users, body=" + resp.body(),
                403,
                resp.statusCode());
    }

    @Test
    public void nonAdmin_isForbiddenOnAdminDatasources_403() throws Exception {
        // A second admin path, so the boundary isn't proven by a single lucky route.
        HttpResponse<String> resp = harness.getAuth(VIEWER_USER, VIEWER_PASS, BASE + "/datasources");
        assertEquals("non-admin must be FORBIDDEN on /admin/datasources, body=" + resp.body(), 403, resp.statusCode());
    }

    @Test
    public void anonymous_isUnauthorizedOnAdmin_401() throws Exception {
        // No Authorization header at all → the gate can't authenticate → 401, distinct from the
        // authenticated-but-role-denied 403 above.
        HttpResponse<String> resp = harness.getAnon(BASE + "/users");
        assertEquals(
                "anonymous request must be UNAUTHORIZED (401), not forbidden (403), body=" + resp.body(),
                401,
                resp.statusCode());
    }

    @Test
    public void nonAdmin_isAllowedOnNonAdminEndpoint_200() throws Exception {
        // Proves the 403s above are a ROLE denial, not a broken credential: the SAME viewer is
        // fully authenticated and reaches a normal isFullyAuthenticated() endpoint. If the viewer
        // credential were simply invalid, this would be 401 and the boundary tests would be
        // meaningless.
        HttpResponse<String> resp = harness.getAuth(VIEWER_USER, VIEWER_PASS, "/rest/saiku/api/discover");
        assertEquals(
                "non-admin must be allowed on a normal isFullyAuthenticated() endpoint (/api/discover),"
                        + " proving the admin 403 is a role denial, body=" + resp.body(),
                200,
                resp.statusCode());
    }
}
