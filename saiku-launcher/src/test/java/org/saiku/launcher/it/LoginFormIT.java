/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#2523 — Spring Security's <em>generated</em> {@code /login} endpoint is
 * gone: the main security chain no longer declares {@code auto-config}, so no
 * form-login filter (POST /login) and no generated "Please sign in" page
 * (GET /login) are registered. That endpoint authenticated real users outside
 * every guard the documented login goes through — outside the rate limiter
 * (LoginRateLimitFilter only watches Authorization-bearing requests), outside
 * CSRF (SaikuCsrfRequestMatcher requires tokens under /rest/ only) and outside
 * the demo email gate (mapped to /rest/* in web.xml) — so a locked-out account
 * could still sign in there, and 302 / vs 302 /login?error was an unlimited
 * password oracle.
 *
 * <p>The one login is POST /rest/saiku/session (rate-limited, audited), plus
 * HTTP Basic for stateless clients and XMLA. This IT pins the deletion so a
 * future {@code auto-config} (or {@code form-login}) reappearing on the chain is
 * an explicit, visible decision.
 */
public class LoginFormIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void generatedLoginPageIsNotServed() throws Exception {
        HttpResponse<String> resp = harness.getAnon("/login");
        assertTrue(
                "GET /login must not serve the generated sign-in page (expected 401/403/404, got " + resp.statusCode()
                        + " with body: " + resp.body() + ")",
                resp.statusCode() == 401 || resp.statusCode() == 403 || resp.statusCode() == 404);
    }

    @Test
    public void postLoginWithRealCredentials_mintsNoSession() throws Exception {
        // Real seed credentials: on the old chain this answered 302 -> / with a
        // JSESSIONID, i.e. a full login bypassing the rate limiter and CSRF.
        HttpRequest req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/login"))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=admin", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = harness.send(req);
        assertTrue(
                "POST /login must not authenticate (expected 401/403/404, got " + resp.statusCode() + "; a 302 with"
                        + " a session cookie would be the rate-limiter-bypassing second login path)",
                resp.statusCode() == 401 || resp.statusCode() == 403 || resp.statusCode() == 404);
        assertEquals(
                "no session may be minted at /login",
                java.util.Optional.empty(),
                resp.headers().allValues("set-cookie").stream()
                        .filter(v -> v.regionMatches(true, 0, "JSESSIONID", 0, "JSESSIONID".length()))
                        .findFirst());
    }
}
