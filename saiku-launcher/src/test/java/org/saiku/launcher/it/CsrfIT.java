/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * CSRF enforcement (saiku#1150).
 *
 * <p>Locks the contract our SPA depends on:
 *
 * <ol>
 *   <li>State-changing /rest/** with a session cookie but no X-XSRF-TOKEN → 403.
 *   <li>State-changing /rest/** with cookie + valid X-XSRF-TOKEN → reaches the handler
 *       (returns whatever the handler returns — we just confirm it's NOT 403).
 *   <li>HTTP Basic POST → exempt (used by IT harnesses and the MCP endpoint).
 *   <li>POST /rest/saiku/session (login) → exempt (bootstrap — no session cookie yet).
 *   <li>Safe methods (GET / HEAD / OPTIONS) → always exempt.
 * </ol>
 *
 * <p>The non-XOR {@code CsrfTokenRequestAttributeHandler} writes the XSRF-TOKEN cookie
 * eagerly on the first GET, which is what the SPA's
 * {@code installAuthInterceptor()} reads from {@code document.cookie}.
 */
public class CsrfIT {

    private static SaikuItHarness harness;
    private static HttpClient client;
    private static String baseUrl;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        baseUrl = harness.baseUrl();
    }

    /**
     * Tear down ScopedRepo's session cache. CsrfIT's form-login leaves an
     * admin-bound session in ScopedRepo's fallback reference; downstream ITs
     * that use Basic auth then see {@code username=admin} via the cached
     * session and trip a latent NPE in BasicRepositoryResource2 (filed
     * separately — the underlying ScopedRepo bleed is documented in
     * decisions/session-fallback-bleed). Logging out here keeps CsrfIT
     * self-contained.
     */
    @AfterClass
    public static void clearSession() throws Exception {
        HttpRequest logout = HttpRequest.newBuilder(URI.create(baseUrl + "/rest/saiku/session"))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        try {
            client.send(logout, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            /* best-effort */
        }
    }

    @Test
    public void basicAuthPostIsExemptFromCsrf() throws Exception {
        // Every existing IT exercises this path implicitly — assert it loud so
        // a future regression in SaikuCsrfRequestMatcher's Authorization carve-out
        // shows up as a focused failure, not a wall of red across the suite.
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/session/foo-not-real", "{}");
        assertNotEquals("Basic-auth POST must not 403 on CSRF", 403, resp.statusCode());
    }

    @Test
    public void loginPostIsExemptFromCsrf() throws Exception {
        // Bootstrap: no session cookie exists yet at login time, so SaikuCsrfRequestMatcher
        // exempts /rest/saiku/session. Without this exemption the SPA could never sign in.
        HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl + "/rest/saiku/session"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=admin", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(login, HttpResponse.BodyHandlers.ofString());
        assertNotEquals("Login POST must not 403 on CSRF", 403, resp.statusCode());
        assertEquals("Login as admin/admin should succeed (200) — body: " + resp.body(), 200, resp.statusCode());
    }

    @Test
    public void formSessionPostWithoutCsrfTokenIs403() throws Exception {
        // Form-login produces a JSESSIONID + an XSRF-TOKEN cookie (eager handler).
        String[] cookies = formLogin();
        String jsessionid = pickCookie(cookies, "JSESSIONID");
        assertNotNull("login should set JSESSIONID; got: " + List.of(cookies), jsessionid);

        // POST to a non-existent path so we exercise the CSRF filter without
        // any handler side effects (rejecting CsrfIT writes was leaking state
        // into the shared harness for downstream ITs). CsrfFilter sits ahead
        // of routing — a CSRF rejection is 403, a routing rejection would be
        // 404. We want the former; that's the assertion.
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/rest/saiku/csrf-it-probe-no-such-path"))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", jsessionid)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(
                "Form-session POST without X-XSRF-TOKEN must be 403 (got: " + resp.statusCode() + ", body: "
                        + resp.body() + ")",
                403,
                resp.statusCode());
    }

    @Test
    public void formSessionPostWithCsrfTokenReachesHandler() throws Exception {
        // Real SPA flow:
        //  1. login → session cookie (token from login response is bound to the
        //     PRE-login session; Spring rotates it on successful auth)
        //  2. GET /rest/saiku/info with the post-login session cookie → fresh
        //     XSRF-TOKEN cookie tied to the live session
        //  3. POST /admin/datasources with that token in both cookie + header
        String[] loginCookies = formLogin();
        String jsessionid = pickCookie(loginCookies, "JSESSIONID");

        HttpRequest probe = HttpRequest.newBuilder(URI.create(baseUrl + "/rest/saiku/info"))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", jsessionid)
                .GET()
                .build();
        HttpResponse<String> probeResp = client.send(probe, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, probeResp.statusCode());
        String[] probeCookies = probeResp.headers().allValues("set-cookie").toArray(new String[0]);
        String xsrfRaw = pickCookieValue(probeCookies, "XSRF-TOKEN");
        assertNotNull("Materializer filter must write XSRF-TOKEN cookie on the GET response", xsrfRaw);

        // Same no-side-effect target: 404 from routing (not 403 from CSRF) proves
        // the token validated and the chain reached the dispatcher.
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/rest/saiku/csrf-it-probe-no-such-path"))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", jsessionid + "; XSRF-TOKEN=" + xsrfRaw)
                .header("X-XSRF-TOKEN", xsrfRaw)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertNotEquals(
                "POST with valid X-XSRF-TOKEN must not be 403 (body: " + resp.body() + ")", 403, resp.statusCode());
    }

    @Test
    public void safeMethodsAreNeverCsrfChecked() throws Exception {
        // GET with a session cookie but no token — should pass through.
        String[] cookies = formLogin();
        String jsessionid = pickCookie(cookies, "JSESSIONID");
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/rest/saiku/info"))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", jsessionid)
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    /** Form-login as admin/admin, return all Set-Cookie headers from the response. */
    private static String[] formLogin() throws Exception {
        HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl + "/rest/saiku/session"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=admin", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals("login must succeed (200), body: " + resp.body(), 200, resp.statusCode());
        return resp.headers().allValues("set-cookie").toArray(new String[0]);
    }

    /** Return the {@code name=value} fragment of a Set-Cookie header (no attributes). */
    private static String pickCookie(String[] setCookies, String name) {
        Optional<String> match = List.of(setCookies).stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst();
        if (match.isEmpty()) return null;
        String raw = match.get();
        int semi = raw.indexOf(';');
        return semi < 0 ? raw : raw.substring(0, semi);
    }

    /** Return just the value portion of a cookie by name. */
    private static String pickCookieValue(String[] setCookies, String name) {
        String pair = pickCookie(setCookies, name);
        if (pair == null) return null;
        int eq = pair.indexOf('=');
        assertTrue("cookie pair must contain '='", eq > 0);
        return pair.substring(eq + 1);
    }
}
