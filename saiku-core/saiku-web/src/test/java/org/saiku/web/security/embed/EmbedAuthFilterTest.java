/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;
import org.saiku.web.security.embed.EmbedAuthFilter.EmbedGuestDetails;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit coverage for {@link EmbedAuthFilter} — the gatekeeper for the
 * {@code <saiku-embed>} read surface. Verifies:
 * <ul>
 *   <li>Non-embed paths pass through untouched.</li>
 *   <li>The mint endpoint passes through (normal Spring auth handles it).</li>
 *   <li>A valid token pins the SecurityContext with {@link EmbedGuestDetails}.</li>
 *   <li>A wrong-kind / wrong-path token replay yields 401 + opaque body —
 *       the token never grants access to anything but the exact pinned
 *       resource.</li>
 *   <li>An expired or revoked token yields the same opaque 401.</li>
 *   <li>A public-grant lookup admits an anonymous request (no token).</li>
 *   <li>A path with neither token nor public grant falls through (Spring's
 *       intercept-url rule will 401).</li>
 *   <li>The SecurityContext is cleared on the way out — no guest identity
 *       leaks into a downstream filter / the session.</li>
 *   <li>Path parsing decodes URL-encoded resource paths so the comparison
 *       against the stored canonical path is byte-faithful.</li>
 * </ul>
 */
public class EmbedAuthFilterTest {

    private EmbedTokenStore tokenStore;
    private EmbedPublicRegistry publicRegistry;
    private EmbedAuthFilter filter;

    @Before
    public void setUp() {
        tokenStore = new EmbedTokenStore((String) null);
        publicRegistry = new EmbedPublicRegistry((String) null);
        filter = new EmbedAuthFilter(tokenStore, publicRegistry);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        System.clearProperty(EmbedPublicRegistry.ALLOW_PUBLIC_PROP);
        System.clearProperty(EmbedAuthFilter.PROP_JWT_SECRET);
        System.clearProperty(EmbedAuthFilter.PROP_JWT_AUDIENCE);
    }

    @Test
    public void non_embed_path_passes_through() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/info");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertTrue("non-embed path must reach the next filter", chain.called);
        assertNull(
                "no auth was set for an untouched request",
                SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void mint_endpoint_passes_through() throws Exception {
        // POST /rest/saiku/api/embed/tokens is the mint surface — must NOT be
        // touched by the guest filter, otherwise an authenticated mint would
        // get its real session blown away.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/rest/saiku/api/embed/tokens");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertTrue(chain.called);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void valid_token_pins_guest_identity() throws Exception {
        EmbedToken t = tokenStore.create(
                "query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"), 60_000L, "label");
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/homes/admin/sales.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue("valid token must reach the next filter", chain.called);
        assertEquals(200, resp.getStatus()); // chain didn't write a status
        assertNotNull("auth must be set during chain", chain.capturedAuth);
        EmbedGuestDetails details = (EmbedGuestDetails) chain.capturedAuth.getDetails();
        assertEquals("query", details.resourceKind);
        assertEquals("/homes/admin/sales.saiku", details.resourcePath);
        assertEquals("admin", details.ownerUser);
        assertEquals(List.of("ROLE_ADMIN"), details.ownerRoles);
        assertFalse("token path is not anonymous", details.isAnonymous());
        assertNull(
                "context must be cleared on exit",
                SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void wrong_resource_path_for_token_is_invalid() throws Exception {
        // Replay attack: a token minted for sales.saiku used against
        // payroll.saiku must NOT authorize the read.
        EmbedToken t = tokenStore.create("query", "/homes/admin/sales.saiku", "admin", List.of(), 60_000L, null);
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/homes/admin/payroll.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertTrue(
                "opaque body — never reveals which exact check failed",
                resp.getContentAsString().contains("EMBED_INVALID"));
        assertFalse("chain must NOT run for an invalid token", chain.called);
    }

    @Test
    public void wrong_kind_for_token_is_invalid() throws Exception {
        // A dashboard-token presented against /query/... is a replay.
        EmbedToken t = tokenStore.create("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), 60_000L, null);
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/homes/admin/exec.saikudash");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(chain.called);
    }

    @Test
    public void revoked_token_is_invalid() throws Exception {
        EmbedToken t = tokenStore.create("query", "/q.saiku", "admin", List.of(), 60_000L, null);
        tokenStore.revoke(t.token);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/q.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(chain.called);
    }

    @Test
    public void public_grant_admits_anonymous_request() throws Exception {
        publicRegistry.grant("dashboard", "/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), "public exec");
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/dashboard/homes/admin/exec.saikudash");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue("public read must reach chain", chain.called);
        EmbedGuestDetails details = (EmbedGuestDetails) chain.capturedAuth.getDetails();
        assertEquals("dashboard", details.resourceKind);
        assertEquals("/homes/admin/exec.saikudash", details.resourcePath);
        assertEquals("admin", details.ownerUser);
        assertTrue("public path is anonymous", details.isAnonymous());
        assertNull(details.token);
    }

    @Test
    public void no_token_no_public_grant_falls_through() throws Exception {
        // Filter doesn't 401 itself; it lets Spring's intercept-url decide.
        // This matches the share-token filter's "transparent pass-through"
        // posture: a missing token on a guarded path is NOT a filter-level
        // error — the security chain wraps everything below it.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/private.saiku");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertTrue("must fall through so Spring 401s with its own posture", chain.called);
        assertEquals(200, resp.getStatus());
    }

    @Test
    public void tile_subpath_authorizes_via_dashboard_token() throws Exception {
        // GET /embed/dashboard/<path>/tile/<id>/query maps to the parent
        // dashboard token — the tile subpath is part of the dashboard's
        // own read API, not a separate resource.
        EmbedToken t = tokenStore.create("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), 60_000L, null);
        MockHttpServletRequest req = new MockHttpServletRequest(
                "POST", "/rest/saiku/api/embed/dashboard/homes/admin/exec.saikudash/tile/tile-42/query");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue(chain.called);
        EmbedGuestDetails details = (EmbedGuestDetails) chain.capturedAuth.getDetails();
        assertEquals("/homes/admin/exec.saikudash", details.resourcePath);
    }

    @Test
    public void app_plugin_html_subpath_authorizes_via_app_token() throws Exception {
        // saiku#1441: GET /embed/app/<path>/plugin/<id>/html maps to the parent app
        // token — the /plugin/ strip must run so the read resolves to the .saikuapp
        // resource, exactly like the /page//tile/ strip does for app tile queries.
        // (Negative control: drop the /plugin/ strip and this 401s — the target path
        // becomes "<app>/plugin/records-bars/html", which no token pins.)
        EmbedToken t =
                tokenStore.create("app", "/homes/admin/portal.saikuapp", "admin", List.of("ROLE_ADMIN"), 60_000L, null);
        MockHttpServletRequest req = new MockHttpServletRequest(
                "GET", "/rest/saiku/api/embed/app/homes/admin/portal.saikuapp/plugin/records-bars/html");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue("valid app token must reach the chain for the plugin-html subpath", chain.called);
        EmbedGuestDetails details = (EmbedGuestDetails) chain.capturedAuth.getDetails();
        assertEquals("app", details.resourceKind);
        assertEquals("/homes/admin/portal.saikuapp", details.resourcePath);
    }

    @Test
    public void app_plugin_html_parse_target_strips_plugin_segment() {
        // Unit-level assertion on the strip itself: the /plugin/<id>/html tail is
        // removed so the resolved resource path is the bare .saikuapp.
        EmbedAuthFilter.ResourceTarget target =
                EmbedAuthFilter.parseTarget("app/homes/admin/portal.saikuapp/plugin/records-bars/html");
        assertNotNull(target);
        assertEquals("app", target.kind);
        assertEquals("/homes/admin/portal.saikuapp", target.path);
    }

    @Test
    public void url_encoded_path_decodes_for_token_match() throws Exception {
        // The stored path is the canonical "homes/admin/My Sales.saiku".
        // The URL sent by the client encodes the space — the filter must
        // decode before comparing, or every space-containing path 401s.
        EmbedToken t = tokenStore.create("query", "/homes/admin/My Sales.saiku", "admin", List.of(), 60_000L, null);
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/homes/admin/My%20Sales.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertTrue(chain.called);
    }

    @Test
    public void context_is_cleared_after_token_request() throws Exception {
        EmbedToken t = tokenStore.create("query", "/q.saiku", "admin", List.of(), 60_000L, null);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/q.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, new MockFilterChain());

        // Critical: never persist a guest auth to the session. The next
        // request that comes through this thread (worker reuse) must NOT
        // inherit the prior guest identity.
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void public_grant_ignored_when_allowPublic_disabled() throws Exception {
        // saiku#1305 — with the deployment switch off, an EXISTING public grant
        // must NOT serve anonymously: the filter skips the registry lookup and
        // falls through to Spring's 401, exactly as if no grant existed.
        // (Negative control: drop the publicEmbedsEnabled() guard and this
        // reverts to the admit path — capturedAuth becomes non-null → RED.)
        System.setProperty(EmbedPublicRegistry.ALLOW_PUBLIC_PROP, "false");
        publicRegistry.grant("dashboard", "/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), "public exec");
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/dashboard/homes/admin/exec.saikudash");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue("must fall through to the Spring chain (which will 401)", chain.called);
        assertNull("public grant must NOT authenticate anonymously while the switch is off", chain.capturedAuth);
        assertNull(
                "no guest identity set when public embeds are disabled",
                SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void public_grant_admits_when_allowPublic_explicitly_true() throws Exception {
        // The default path still works when the switch is explicitly on.
        System.setProperty(EmbedPublicRegistry.ALLOW_PUBLIC_PROP, "true");
        publicRegistry.grant("dashboard", "/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), "public exec");
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/dashboard/homes/admin/exec.saikudash");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue(chain.called);
        assertNotNull("explicit allowPublic=true still admits the public grant", chain.capturedAuth);
        EmbedGuestDetails details = (EmbedGuestDetails) chain.capturedAuth.getDetails();
        assertTrue("public path is anonymous", details.isAnonymous());
    }

    /* ----------------------- saiku#1104: embed JWT ----------------------- */

    private static final String JWT_SECRET = "embed-rls-test-secret-at-least-32-bytes!!";

    @Test
    public void valid_jwt_pins_guest_with_sub_and_forced_filters() throws Exception {
        System.setProperty(EmbedAuthFilter.PROP_JWT_SECRET, JWT_SECRET);
        String jwt = mintJwt(
                "{\"sub\":\"u_99\",\"saiku.resourceKind\":\"query\","
                        + "\"saiku.resourcePath\":\"/homes/admin/sales.saiku\","
                        + "\"saiku.owner\":\"admin\",\"saiku.ownerRoles\":[\"ROLE_ADMIN\"],"
                        + "\"saiku.filters\":[{\"dimension\":\"Customer\",\"op\":\"in\",\"members\":[\"[Customer].[acme]\"]}],"
                        + "\"exp\":" + future() + "}",
                JWT_SECRET);
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/homes/admin/sales.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue("valid JWT must reach the chain", chain.called);
        EmbedGuestDetails d = (EmbedGuestDetails) chain.capturedAuth.getDetails();
        assertEquals("u_99", d.jwtSub);
        assertEquals("admin", d.ownerUser);
        assertEquals(List.of("ROLE_ADMIN"), d.ownerRoles);
        assertNotNull("forced filters carried forward", d.forcedFiltersJson);
        assertTrue(d.forcedFiltersJson.contains("Customer"));
        assertEquals("query", d.resourceKind);
        assertEquals("/homes/admin/sales.saiku", d.resourcePath);
    }

    @Test
    public void forged_jwt_is_invalid() throws Exception {
        System.setProperty(EmbedAuthFilter.PROP_JWT_SECRET, JWT_SECRET);
        // signed with a different secret than the deployment's
        String jwt = mintJwt(
                "{\"sub\":\"u\",\"saiku.resourceKind\":\"query\"," + "\"saiku.resourcePath\":\"/q.saiku\",\"exp\":"
                        + future() + "}",
                "a-totally-different-secret-key-32bytes-xx");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/q.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("EMBED_INVALID"));
        assertFalse("forged JWT must NOT reach the chain", chain.called);
    }

    @Test
    public void expired_jwt_is_invalid() throws Exception {
        System.setProperty(EmbedAuthFilter.PROP_JWT_SECRET, JWT_SECRET);
        String jwt = mintJwt(
                "{\"sub\":\"u\",\"saiku.resourceKind\":\"query\"," + "\"saiku.resourcePath\":\"/q.saiku\",\"exp\":"
                        + past() + "}",
                JWT_SECRET);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/q.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(chain.called);
    }

    @Test
    public void jwt_for_wrong_resource_is_invalid() throws Exception {
        // Replay: a JWT minted for sales.saiku presented against payroll.saiku.
        System.setProperty(EmbedAuthFilter.PROP_JWT_SECRET, JWT_SECRET);
        String jwt = mintJwt(
                "{\"sub\":\"u\",\"saiku.resourceKind\":\"query\","
                        + "\"saiku.resourcePath\":\"/homes/admin/sales.saiku\",\"exp\":" + future() + "}",
                JWT_SECRET);
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/homes/admin/payroll.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(chain.called);
    }

    @Test
    public void jwt_rejected_when_no_secret_configured() throws Exception {
        // No PROP_JWT_SECRET set -> a presented JWT can't be verified -> reject
        // (fail-closed), never accept unverified input.
        String jwt = mintJwt(
                "{\"sub\":\"u\",\"saiku.resourceKind\":\"query\"," + "\"saiku.resourcePath\":\"/q.saiku\",\"exp\":"
                        + future() + "}",
                JWT_SECRET);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/api/embed/query/q.saiku");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(chain.called);
    }

    private static long future() {
        return System.currentTimeMillis() / 1000L + 3600;
    }

    private static long past() {
        return System.currentTimeMillis() / 1000L - 3600;
    }

    private static String mintJwt(String payloadJson, String secret) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String h = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String p = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = b64.encodeToString(mac.doFinal((h + "." + p).getBytes(StandardCharsets.UTF_8)));
            return h + "." + p + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* --------------------------- helpers ---------------------------- */

    /** Records whether the chain was called; does NOT capture auth state. */
    private static class TrackingChain extends MockFilterChain {
        boolean called = false;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse resp) {
            called = true;
        }
    }

    /** Captures the SecurityContext at the moment the chain proceeds — so
     *  the test sees the auth as the downstream resource would. */
    private static class ContextCapturingChain extends MockFilterChain {
        boolean called = false;
        Authentication capturedAuth;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse resp) {
            called = true;
            capturedAuth = SecurityContextHolder.getContext().getAuthentication();
        }
    }
}
