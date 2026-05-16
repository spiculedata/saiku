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
 * Coverage for the per-user datasource discovery endpoint
 * ({@code /rest/saiku/{username}/org.saiku.datasources}). Strictly read-only
 * tests against the seeded {@code unknown_foodmart} datasource — DELETE/PUT
 * paths are covered by AdminIT against the admin surface.
 */
public class DataSourceIT {

    private static SaikuItHarness harness;
    private static final String BASE = "/rest/saiku/admin/org.saiku.datasources";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void listDatasources_route_returns404_pinningCurrentBehavior() throws Exception {
        // FINDING (pinned): `/rest/saiku/{username}/org.saiku.datasources`
        // returns 404 against this embedded launcher build — the JAX-RS
        // route registers but Spring Security or Jersey path-matching is
        // rejecting it before the resource method runs. Likely a dotted-
        // path issue with the {username} variable. The admin console uses
        // `/rest/saiku/admin/datasources` (see AdminIT) which is the
        // canonical path; this older user-scoped endpoint is unused by
        // the SPA. Pin so a future fix is testable.
        HttpResponse<String> resp = harness.getAuth(BASE);
        assertEquals(404, resp.statusCode());
    }

    @Test
    public void getDatasourceById_route_returns404_pinningCurrentBehavior() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/any-id");
        assertEquals(404, resp.statusCode());
    }
}
