/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.scim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.web.scim.ScimRateLimiter;
import org.saiku.web.scim.ScimToken;
import org.saiku.web.scim.ScimTokenStore;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1438 — the SCIM authentication boundary.
 *
 * <p>Three properties are load-bearing and asserted here rather than assumed: the surface is
 * isolated (a non-SCIM path is a transparent pass-through, so a SCIM bearer can never leak into
 * another endpoint), a missing/unknown bearer is a 401 with a SCIM-shaped body rather than an
 * empty Spring error, and the accepted case leaves <b>no</b> authentication behind once the chain
 * unwinds — a request-scoped principal, not a session.
 */
public class ScimAuthFilterTest {

    private ScimTokenStore store;
    private String secret;

    @Before
    public void setUp() {
        store = new ScimTokenStore(null);
        secret = store.mint("Okta production", "Okta", "admin").secret;
        SecurityContextHolder.clearContext();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String uri, String bearer) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        return req;
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    private ScimAuthFilter filter(int maxPerWindow) {
        return new ScimAuthFilter(store, new ScimRateLimiter(maxPerWindow, 60_000L));
    }

    @Test
    public void validBearerEstablishesAScimPrincipal() throws Exception {
        MockHttpServletResponse resp = response();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<ScimToken> token = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            token.set(ScimAuthFilter.currentToken());
        };

        filter(100).doFilter(request("/rest/scim/v2/Users", secret), resp, chain);

        assertEquals(200, resp.getStatus());
        Authentication auth = seen.get();
        assertNotNull("the chain must run with a SCIM principal", auth);
        assertEquals(
                ScimAuthFilter.SCIM_ROLE,
                auth.getAuthorities().iterator().next().getAuthority());
        // The token metadata is what the audit line carries (label + IdP), and the resources
        // read it from the same place.
        assertNotNull(token.get());
        assertEquals("Okta", token.get().idp);
        assertEquals("Okta production", token.get().label);
    }

    @Test
    public void principalIsNeverLeftBehindAfterTheChain() throws Exception {
        FilterChain chain = (req, res) -> {
            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        };
        filter(100).doFilter(request("/rest/scim/v2/Users", secret), response(), chain);
        assertNull(
                "a SCIM principal must not outlive the request",
                SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void missingBearerIs401WithAScimErrorBody() throws Exception {
        MockHttpServletResponse resp = response();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain =
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication());

        filter(100).doFilter(request("/rest/scim/v2/Users", null), resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull("the chain must not run unauthenticated", seen.get());
        assertTrue(resp.getContentType().startsWith("application/scim+json"));
        assertTrue(resp.getContentAsString().contains("urn:ietf:params:scim:api:messages:2.0:Error"));
    }

    @Test
    public void unknownBearerIsIndistinguishableFromMissing() throws Exception {
        MockHttpServletResponse resp = response();
        filter(100).doFilter(request("/rest/scim/v2/Users", "not-a-real-token"), resp, (req, res) -> {
            throw new AssertionError("must not reach the chain");
        });
        assertEquals(401, resp.getStatus());
    }

    @Test
    public void basicAuthorizationIsNotAValidScimBearer() throws Exception {
        // A Saiku account (admin/admin) must not be able to drive provisioning.
        MockHttpServletResponse resp = response();
        filter(100).doFilter(request("/rest/scim/v2/Users", "YWRtaW46YWRtaW4="), resp, (req, res) -> {
            throw new AssertionError("must not reach the chain");
        });
        assertEquals(401, resp.getStatus());
    }

    @Test
    public void revokedBearerStopsWorkingImmediately() throws Exception {
        ScimTokenStore.MintedToken minted = store.mint("Entra", "Microsoft Entra ID", "admin");
        store.revoke(minted.token.id);
        MockHttpServletResponse resp = response();
        filter(100).doFilter(request("/rest/scim/v2/Users", minted.secret), resp, (req, res) -> {
            throw new AssertionError("must not reach the chain");
        });
        assertEquals(401, resp.getStatus());
    }

    @Test
    public void overBudgetIs429WithRetryAfter() throws Exception {
        ScimAuthFilter f = filter(1);
        f.doFilter(request("/rest/scim/v2/Users", secret), response(), (req, res) -> {});
        MockHttpServletResponse second = response();
        f.doFilter(request("/rest/scim/v2/Users", secret), second, (req, res) -> {
            throw new AssertionError("a throttled call must not reach the chain");
        });
        assertEquals(429, second.getStatus());
        assertEquals("60", second.getHeader("Retry-After"));
    }

    @Test
    public void rateLimitIsPerToken() throws Exception {
        ScimAuthFilter f = filter(1);
        String other = store.mint("Entra", "Entra", "admin").secret;
        f.doFilter(request("/rest/scim/v2/Users", secret), response(), (req, res) -> {});
        MockHttpServletResponse resp = response();
        f.doFilter(request("/rest/scim/v2/Users", other), resp, (req, res) -> {});
        assertEquals("a different token has its own budget", 200, resp.getStatus());
    }

    @Test
    public void nonScimPathsAreUntouched() throws Exception {
        MockHttpServletResponse resp = response();
        filter(1).doFilter(request("/rest/saiku/api/users", secret), resp, (req, res) -> {});
        assertEquals(200, resp.getStatus());
        assertNull(
                "no SCIM principal outside the SCIM surface",
                SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void scimPathMatchingIsExactAboutThePrefix() {
        assertTrue(ScimAuthFilter.isScimPath("/rest/scim/v2"));
        assertTrue(ScimAuthFilter.isScimPath("/rest/scim/v2/Users/1"));
        org.junit.Assert.assertFalse(ScimAuthFilter.isScimPath("/rest/scim/v2x/Users"));
        org.junit.Assert.assertFalse(ScimAuthFilter.isScimPath("/rest/scim/"));
        org.junit.Assert.assertFalse(ScimAuthFilter.isScimPath(null));
    }
}
