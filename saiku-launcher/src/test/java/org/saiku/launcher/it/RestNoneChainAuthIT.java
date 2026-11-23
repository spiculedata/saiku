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
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#1951 reversion guard (broken access control — the same class of bug as the {@code /xmla}
 * gap #1905 fixed, applied to {@code /rest/*}).
 *
 * <p><b>The bug.</b> Jersey is prefix-mapped at {@code /rest/*} (web.xml). Spring Security's
 * {@code <security:http pattern="...">} chains match the servlet-RELATIVE lookup path (pathInfo).
 * For {@code POST /rest/} the pathInfo is {@code "/"}, which the {@code pattern="/"}
 * {@code security="none"} chain matched — and a {@code security="none"} chain installs NO filters
 * at all. So {@code /rest/}, {@code /rest/ui/**}, {@code /rest/images/**},
 * {@code /rest/repository/**}, {@code /rest/showcase/**}, {@code /rest/index.jsp} and friends all
 * reached Jersey with no authentication, no CSRF, no rate-limit and no SecurityContext. No
 * {@code intercept-url} in the main chain ever saw them, because the request never got there.
 *
 * <p>Today it is latent, not live: every JAX-RS resource in saiku-web is class-rooted at
 * {@code /saiku/…}, so all of those stolen path shapes 404. The hazard is that the day someone
 * mounts a resource at a bare {@code /} or under one of those prefixes it becomes anonymous by
 * construction, and no CI check fails. This IT pins the fix so that day fails here instead.
 *
 * <p><b>The fix.</b> Every {@code security="none"} chain in {@code applicationContext-saiku.xml}
 * declares {@code request-matcher="ant"}, so it matches the ABSOLUTE path and can no longer claim
 * a servlet's pathInfo. That is behaviour-preserving for the intended static paths: they all belong
 * to the default servlet, which is not prefix-mapped, so its lookup path IS the absolute path. The
 * two dead {@code /rest/saiku/info*} none-chains were DELETED rather than converted — see the
 * comment in the XML for why converting them would have been a downgrade.
 *
 * <p>The assertions target the security property, not one exact status. An anonymous
 * {@code /rest/…}-shaped request must be REJECTED by the main chain before Jersey is reached; 401
 * (authentication) and 403 (CSRF, which guards state-changing {@code /rest/**} methods) are both
 * proofs that a real filter chain handled it. A bare 404 would mean the request reached Jersey with
 * NO filters at all — the pre-fix failure mode — so 404 is explicitly excluded.
 */
public class RestNoneChainAuthIT {

    /**
     * Shapes that, pre-fix, were each claimed by a servlet-relative {@code security="none"} chain
     * (via pathInfo). Every one must now land in the main chain and be rejected.
     */
    private static final List<String> STOLEN_SHAPES = Arrays.asList(
            "/rest/",
            "/rest/ui/x",
            "/rest/ui/settings",
            "/rest/images/logo.png",
            "/rest/repository/dashboard",
            "/rest/showcase/index.html",
            "/rest/embed-demo/app.js",
            "/rest/aqvira-demo/main.js",
            "/rest/index.jsp",
            "/rest/style.css");

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    /** Anonymous POST of a JSON body at {@code path} — CSRF-guard shape, like the SPA's XHRs. */
    private static HttpResponse<String> postAnon(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return harness.send(req);
    }

    private static void assertReachedSecuredChain(String path) throws Exception {
        HttpResponse<String> resp = postAnon(path);
        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();

        assertNotEquals(
                "Anonymous POST " + path + " reached Jersey with NO Spring Security filters — a "
                        + "security=\"none\" chain claimed its servlet-relative pathInfo (status="
                        + status + "). Every security=\"none\" chain must declare "
                        + "request-matcher=\"ant\" (saiku#1951).",
                404,
                status);
        assertTrue(
                "Anonymous POST " + path + " must be rejected by Spring Security before Jersey is "
                        + "reached (401/403 expected, got status=" + status + ", body: " + body + ")",
                status == 401 || status == 403);
    }

    @Test
    public void anonymousPostToRestRootIsGated() throws Exception {
        // pathInfo "/" — the exact shape the pattern="/" none chain used to steal.
        assertReachedSecuredChain("/rest/");
    }

    @Test
    public void anonymousPostToRestStaticPrefixesIsGated() throws Exception {
        for (String shape : STOLEN_SHAPES) {
            assertReachedSecuredChain(shape);
        }
    }

    /**
     * The other half of the fix: {@code /rest/saiku/info*} used to have its own (dead) none-chains.
     * Those were DELETED, not converted — so the endpoint must still be reachable anonymously, via
     * the main chain's {@code permitAll} rule, i.e. anonymous access WITH a real SecurityContext.
     * A 401 here would mean someone "fixed" the dead chains by making them match, locking the SPA's
     * pre-auth login-page capability probe out of the app.
     */
    @Test
    public void anonymousInfoEndpointStillReachable() throws Exception {
        HttpResponse<String> resp = harness.getAnon("/rest/saiku/info");
        assertEquals(
                "/rest/saiku/info must stay anonymously reachable — it is served by the main chain's "
                        + "permitAll rule, with a real SecurityContext, not by a deleted none-chain "
                        + "(status=" + resp.statusCode() + ", body: " + resp.body() + ")",
                200,
                resp.statusCode());
    }

    /**
     * Ant-matching the none-chains must not over-restrict the static paths they are meant to
     * permit: {@code /}, {@code /index.jsp}, {@code /style.css} and {@code /ui/**} must still be
     * served without authentication.
     */
    @Test
    public void staticPathsRemainAnonymous() throws Exception {
        for (String path : Arrays.asList("/", "/index.jsp", "/style.css", "/ui/", "/ui/index.html")) {
            HttpResponse<String> resp = harness.getAnon(path);
            assertNotEquals(
                    "GET " + path + " is a public static path and must NOT be behind authentication "
                            + "after the saiku#1951 ant-matching change (status=" + resp.statusCode() + ")",
                    401,
                    resp.statusCode());
        }
    }

    /**
     * The one deliberate behaviour shift, pinned so it can't drift silently.
     *
     * <p>BrandingServlet is prefix-mapped {@code /ui/branding/*}, so its servlet-RELATIVE lookup
     * path was {@code "/x.css"} — which matched no none chain, and {@code /ui/branding/*} has no
     * {@code intercept-url} in the main chain, so it was served anonymously by the MAIN chain.
     * Under ant matching it joins {@code /ui/**} and is served by that none chain instead. Both
     * are anonymous, which is the property that matters; this test documents the change so a
     * future reader who sees the chain index shift knows it was intentional, not a regression.
     */
    @Test
    public void brandingOverlayRemainsAnonymous() throws Exception {
        // No branding file exists in a fresh IT home, so BrandingServlet 404s by design — but
        // the point is the STATUS SHAPE: it must be reached at all, never 401/403 from a chain
        // that decided the public /ui/** prefix needed authentication.
        HttpResponse<String> resp = harness.getAnon("/ui/branding/does-not-exist.css");
        assertNotEquals(
                "GET /ui/branding/* must stay public — it is a theme overlay under the public "
                        + "/ui/** prefix (status=" + resp.statusCode() + ")",
                401,
                resp.statusCode());
    }
}
