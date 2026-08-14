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
 * saiku#1751 — defence-in-depth Spring URL gates for the single-layer-guarded admin resources.
 *
 * <p>{@code StatisticsResource} ({@code /rest/saiku/statistics/**}) and {@code DataSourceResource}
 * ({@code /rest/saiku/{username}/org.saiku.datasources/**}) are guarded ONLY by JAX-RS
 * {@code @RolesAllowed("ROLE_ADMIN")} — there is no {@code /admin/**} URL gate over them. saiku#1732
 * made that JAX-RS layer deterministic (the primary fix); saiku#1751 adds a matching Spring
 * {@code intercept-url ... hasRole('ADMIN')} in front so a future {@code @RolesAllowed} typo — or a
 * re-enabled {@code DataSourceResource} without the annotation — can't silently expose these.
 *
 * <p><b>Why DataSourceResource is the load-bearing lock for #1751.</b> Its bean is commented out in
 * {@code saiku-beans.xml}, so the resource is unmounted (404s). Spring Security runs BEFORE Jersey,
 * so the URL gate is what determines the outcome, and its effect is directly observable — it flips
 * the response from the pre-#1751 baseline to the gated one:
 *
 * <pre>
 *   request                          without the #1751 gate            with the #1751 gate
 *   ------------------------------   ------------------------------   --------------------------
 *   non-admin  to datasources path   catch-all isFullyAuthenticated   URL gate denies -> 403
 *                                    -> authenticated -> Jersey -> 404
 *   anonymous  to datasources path   catch-all isFullyAuthenticated   URL gate can't auth -> 401
 *                                    -> 401 (already)
 * </pre>
 *
 * <p>The non-admin -&gt; 403 (not 404) assertion is the toothful proof of this PR: remove the
 * datasources intercept-url (pattern {@code /rest/saiku/<user>/org.saiku.datasources/}...) and it
 * reverts to 404 (the request reaches the unmounted resource) -&gt; the test goes red. It proves the
 * URL gate protects a FUTURE re-enable of DataSourceResource independently of any
 * {@code @RolesAllowed} annotation.
 *
 * <p>The StatisticsResource assertions below overlap AdminIT's saiku#1732 coverage by intent: there
 * they lock the JAX-RS layer; here they lock the belt-and-braces URL gate. Keeping both means neither
 * layer can regress silently.
 */
public class UrlGateHardeningIT {

    private static SaikuItHarness harness;

    // saiku#1732 harness seed: a non-admin (ROLE_USER only, password "admin") alongside admin.
    private static final String VIEWER_USER = "viewer";
    private static final String VIEWER_PASS = "admin";

    // A concrete DataSourceResource path. The {username} segment is deliberately NOT "admin" — a
    // path under /rest/saiku/admin/** would match the EXISTING admin-console URL gate and confound
    // the proof. With "viewer", the path falls to the generic /rest/** rule ABSENT the #1751 gate
    // (→ 404 on the unmounted resource for an authenticated caller), and is denied 403 WITH it.
    private static final String DATASOURCES = "/rest/saiku/viewer/org.saiku.datasources";

    private static final String STATS = "/rest/saiku/statistics/mondrian";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    // ---- DataSourceResource URL gate: the toothful #1751-specific lock ----

    @Test
    public void datasources_nonAdmin_isForbiddenByUrlGate_403_not404() throws Exception {
        // WITHOUT the #1751 gate this is 404 (authenticated → falls to /rest/**, reaches the
        // unmounted resource). WITH the gate it's 403 (denied at the URL layer before Jersey).
        // The 403-not-404 distinction is the proof the URL gate — not the JAX-RS layer — fired.
        HttpResponse<String> resp = harness.getAuth(VIEWER_USER, VIEWER_PASS, DATASOURCES + "/anything");
        assertEquals(
                "non-admin must be FORBIDDEN (403) at the URL gate on the datasources path, not reach it (404),"
                        + " body=" + resp.body(),
                403,
                resp.statusCode());
    }

    @Test
    public void datasources_nonAdmin_bareClassPath_isForbidden_403() throws Exception {
        // The most sensitive DataSourceResource method — getDatasources() — is served at the BARE
        // class path (no trailing segment); it returns full connection config INCLUDING backend
        // passwords. Assert the ant pattern `/rest/saiku/*/org.saiku.datasources/**` still covers the
        // zero-trailing-segment case (Spring AntPathMatcher: `/x/**` matches `/x`). If it didn't, the
        // gate would miss exactly the route that leaks credentials — so this pins that coverage.
        HttpResponse<String> resp = harness.getAuth(VIEWER_USER, VIEWER_PASS, DATASOURCES);
        assertEquals(
                "non-admin must be FORBIDDEN (403) on the BARE datasources class path (the credential-"
                        + "leaking getDatasources route), body=" + resp.body(),
                403,
                resp.statusCode());
    }

    @Test
    public void datasources_anonymous_isUnauthorized_401() throws Exception {
        // No Authorization header → the gate can't authenticate → 401, distinct from the
        // authenticated-but-role-denied 403 above.
        HttpResponse<String> resp = harness.getAnon(DATASOURCES + "/anything");
        assertEquals(
                "anonymous request on the datasources path must be UNAUTHORIZED (401), body=" + resp.body(),
                401,
                resp.statusCode());
    }

    // ---- StatisticsResource URL gate: belt-and-braces over the JAX-RS layer (AdminIT locks JAX-RS) ----

    @Test
    public void statistics_asAdmin_ok() throws Exception {
        // The gate admits a genuine admin; the resource is LIVE, so this is a real 200.
        HttpResponse<String> resp = harness.getAuth(STATS);
        assertEquals(
                "admin must reach StatisticsResource through the URL gate (200), body=" + resp.body(),
                200,
                resp.statusCode());
    }

    @Test
    public void statistics_nonAdmin_isForbidden_403() throws Exception {
        // Now denied by BOTH the URL gate (saiku#1751) and the JAX-RS @RolesAllowed (saiku#1732);
        // either layer alone yields 403, so this stays green if one regresses — which is the point
        // of defence in depth.
        HttpResponse<String> resp = harness.getAuth(VIEWER_USER, VIEWER_PASS, STATS);
        assertEquals(
                "non-admin must be FORBIDDEN (403) on StatisticsResource, body=" + resp.body(), 403, resp.statusCode());
    }

    @Test
    public void statistics_anonymous_isUnauthorized_401() throws Exception {
        HttpResponse<String> resp = harness.getAnon(STATS);
        assertEquals(
                "anonymous must be UNAUTHORIZED (401) on StatisticsResource, body=" + resp.body(),
                401,
                resp.statusCode());
    }
}
