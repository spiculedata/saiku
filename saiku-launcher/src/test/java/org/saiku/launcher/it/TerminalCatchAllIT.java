/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#1949 — the active {@code <security:http>} chain now ends in a TERMINAL catch-all
 * {@code <security:intercept-url pattern="/**" access="isFullyAuthenticated"/>}, so an unlisted URL
 * fails CLOSED instead of open.
 *
 * <p>Before the fix, the chain's last rule was {@code /WEB-INF/classes/legacy-schema} and nothing
 * after it. Spring Security's {@code RequestMatcherDelegatingAuthorizationManager} returns a NULL
 * decision for a request that matches no {@code intercept-url}, and a null decision is GRANTED —
 * so every URL not explicitly listed was anonymous by omission. That is the class-level root cause
 * of how {@code /xmla} was reachable with no credentials before saiku#1905, and it stays a footgun
 * for any servlet or handler added later that no {@code security="none"} chain claims.
 *
 * <p>This IT locks BOTH halves of the change, because a fail-closed default that breaks a
 * legitimate anonymous surface is not shippable:
 *
 * <ol>
 *   <li><b>Fails closed.</b> Anonymous requests to unlisted, unmapped paths — the exact shape a
 *       future handler would have — are rejected with 401 instead of being served. Removing the
 *       catch-all turns each of these red (they become 404-after-serve / 200, never a 401).
 *   <li><b>Doesn't over-block.</b> Every deliberately-public surface stays reachable with no
 *       credentials (the {@code security="none"} chains for the SPA, the demo/showcase pages, the
 *       repository and the root landing page, plus the main chain's own permitAll rules and the
 *       dedicated {@code /xmla/**} chain), and an AUTHENTICATED request to an unlisted path still
 *       reaches the container (404 from the unmapped handler) rather than being rejected at the
 *       URL layer — proving the catch-all gates authentication, not existence.
 * </ol>
 *
 * <p>Driven through the REAL Jetty + Spring Security filter chain via {@link SaikuItHarness}, the
 * only place the filter-chain ORDER and the {@code intercept-url} ORDER in
 * {@code applicationContext-saiku.xml} are both actually observable.
 */
public class TerminalCatchAllIT {

    private static SaikuItHarness harness;

    /**
     * Paths no {@code intercept-url} and no {@code security="none"} chain claims. The container has
     * no handler for them, so the ONLY thing that can decide their fate is the terminal catch-all
     * — which is exactly the point. Each one is a plausible future servlet mount, i.e. precisely
     * the "added later without a matching rule" case the issue is about.
     */
    private static final String[] UNLISTED_PATHS = {
        "/unlisted-handler",
        "/unlisted/anything/deeper",
        "/actuator/health",
        "/management/info",
        "/internal/debug",
        "/legacy-schema",
    };

    /**
     * Deliberately public surfaces. Each is claimed by a {@code security="none"} chain declared
     * ABOVE the secured chain, or by an explicit permitAll in it, so none of them may regress to
     * 401 under the catch-all. They are asserted as "not 401" rather than a fixed status: several
     * are static files whose 200/404/302 depends on the built bundle, and the security property
     * under test is only that the URL layer does not demand credentials.
     */
    private static final String[] PUBLIC_PATHS = {
        "/", // index.jsp — meta-refreshes to /ui/
        "/index.jsp",
        "/style.css",
        "/images", // bare directory (saiku#1949 explicit none chain)
        "/images/logo.svg",
        "/ui", // BARE "/ui" — the most hand-typed URL in the product (saiku#1949 none chain)
        "/ui/", // the SPA itself
        "/ui/showcase/", // public showcase SPA (served under the SPA's /ui/** none chain)
        "/embed-demo/", // public OEM demo page
        "/aqvira-demo/", // public OEM demo page
        "/repository/", // none chain; unmounted in the shipped build -> 404, not 401
        "/rest/saiku/info", // permitAll in the main chain
        "/rest/saiku/info/ui-settings", // permitAll in the main chain (saiku#1951)
        "/serverdocs/does-not-exist", // isAnonymous() rule
        "/favicon.ico" // saiku#1949 explicit permit (404 today, must not become 401)
    };

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    // ---- fail closed: unlisted URLs are no longer anonymous by default ----

    @Test
    public void anonymousRequestToUnlistedPathIsUnauthorized() throws Exception {
        for (String path : UNLISTED_PATHS) {
            HttpResponse<String> resp = harness.getAnon(path);
            assertEquals(
                    "anonymous GET " + path + " matches no explicit intercept-url, so the TERMINAL"
                            + " catch-all (saiku#1949) must require authentication — a null"
                            + " authorization decision would have served it anonymously"
                            + " (status=" + resp.statusCode() + ", body=" + resp.body() + ")",
                    401,
                    resp.statusCode());
        }
    }

    @Test
    public void anonymousPostToUnlistedPathIsUnauthorized() throws Exception {
        // Method-independence: the catch-all is an authorization rule, so a state-changing verb
        // must not slip past it either. Built by hand rather than via the harness's postAuthForm
        // helper, which always attaches admin Basic auth.
        HttpRequest req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/unlisted-handler"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("a=b"))
                .build();
        HttpResponse<String> resp = harness.send(req);
        assertEquals(
                "anonymous POST to an unlisted path must be UNAUTHORIZED (401), body=" + resp.body(),
                401,
                resp.statusCode());
    }

    @Test
    public void authenticatedRequestToUnlistedPathReachesTheContainer() throws Exception {
        // The catch-all gates AUTHENTICATION, not existence: a logged-in caller on an unmapped path
        // gets the container's 404, not a URL-layer rejection. Without this, an over-broad rule
        // (e.g. hasRole('ADMIN'), or a rule ordered before /rest/**) would be indistinguishable
        // from the intended behaviour by the 401-only assertions above.
        for (String path : UNLISTED_PATHS) {
            HttpResponse<String> resp = harness.getAuth(path);
            assertNotEquals(
                    "authenticated admin must NOT be rejected at the URL layer for " + path
                            + " — the catch-all is isFullyAuthenticated(), not role-restricted"
                            + " (status=" + resp.statusCode() + ", body=" + resp.body() + ")",
                    403,
                    resp.statusCode());
        }
    }

    // ---- don't over-block: every deliberately-public surface stays anonymous ----

    @Test
    public void deliberatelyPublicSurfacesStayAnonymous() throws Exception {
        for (String path : PUBLIC_PATHS) {
            HttpResponse<String> resp = harness.getAnon(path);
            assertNotEquals(
                    "anonymous GET " + path + " is a deliberately public surface (a security=\"none\""
                            + " chain or an explicit permitAll) and must not be caught by the"
                            + " saiku#1949 terminal catch-all (status=" + resp.statusCode()
                            + ", body=" + resp.body() + ")",
                    401,
                    resp.statusCode());
        }
    }

    @Test
    public void catchAllRejectionCarriesNoBrowserAuthChallenge() throws Exception {
        // The chain's entry point is basicAuth401NoChallenge, so the new 401s on unlisted paths must
        // stay challenge-free — otherwise a stray anonymous request to a not-yet-mounted handler
        // pops a native Basic-auth dialog in the operator's browser, which is a support burden the
        // pre-fix behaviour (404) did not have.
        HttpResponse<String> resp = harness.getAnon("/unlisted-handler");
        assertEquals(
                "expected the catch-all to reject an unlisted path (status=" + resp.statusCode() + ")",
                401,
                resp.statusCode());
        assertTrue(
                "the 401 on an unlisted path must NOT carry a WWW-Authenticate challenge (the chain's"
                        + " entry point is basicAuth401NoChallenge) — headers="
                        + resp.headers().map(),
                resp.headers().firstValue("www-authenticate").isEmpty());
    }

    @Test
    public void uiShellIsServedAnonymously() throws Exception {
        // The load-bearing one: an operator opening http://host:8080/ as an anonymous visitor must
        // still get the SPA shell. Asserted positively (200 + html) because it is the surface a
        // mis-ordered catch-all would break first, and a 401/403 there is the loudest possible
        // regression.
        HttpResponse<String> resp = harness.getAnon("/ui/");
        assertEquals(
                "the SPA shell must remain anonymously reachable at /ui/ under the terminal" + " catch-all (status="
                        + resp.statusCode() + ", body=" + resp.body() + ")",
                200,
                resp.statusCode());
        assertTrue(
                "expected the SPA shell HTML at /ui/, got: " + resp.body(),
                resp.body() != null && resp.body().toLowerCase().contains("<html"));
    }

    @Test
    public void rootLandingPageRedirectsToUiAnonymously() throws Exception {
        HttpResponse<String> resp = harness.getAnon("/");
        assertEquals(
                "the root landing page (index.jsp) must remain anonymously reachable and follow its"
                        + " meta-refresh/redirect to the SPA (status=" + resp.statusCode() + ")",
                200,
                resp.statusCode());
    }

    @Test
    public void bareUiPathRedirectsAnonymously() throws Exception {
        // http://host:8080/ui with no trailing slash: the container issues its directory redirect
        // to /ui/, and the SPA fallback then serves the shell. Pinned because it is the one
        // hand-typed URL the catch-all would otherwise lock a user out of.
        HttpResponse<String> resp = harness.getAnon("/ui");
        assertEquals(
                "the bare /ui path must remain anonymously reachable and redirect into the SPA" + " (status="
                        + resp.statusCode() + ", body=" + resp.body() + ")",
                200,
                resp.statusCode());
    }
}
