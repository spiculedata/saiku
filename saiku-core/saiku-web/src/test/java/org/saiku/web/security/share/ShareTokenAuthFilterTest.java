/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.web.schedule.OwnerIdentity;
import org.saiku.web.schedule.OwnerIdentityResolver;
import org.saiku.web.security.share.ShareTokenAuthFilter.ShareGuestDetails;
import org.saiku.web.share.ShareToken;
import org.saiku.web.share.ShareTokenStore;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1920 — a share link must not outlive its owner's account.
 *
 * <p>The share token stores {@code ownerRolesSnapshot} at mint time. Before this
 * change the filter trusted it forever, so disabling or demoting the owner left
 * every outstanding share link reading the dashboard under the old — possibly
 * admin — data scope. These tests lock the live re-resolution: current roles
 * win, and an absent owner is the same opaque {@code SHARE_INVALID} a bad token
 * gets.
 */
public class ShareTokenAuthFilterTest {

    private static final String VIEW_PATH = "/rest/saiku/share/view/homes/admin/exec.saikudash";

    private ShareTokenStore store;
    private MutableOwnerResolver ownerResolver;
    private ShareTokenAuthFilter filter;

    @Before
    public void setUp() {
        store = new ShareTokenStore((String) null);
        ownerResolver = new MutableOwnerResolver();
        ownerResolver.present = true;
        ownerResolver.roles = List.of("ROLE_ADMIN");
        filter = new ShareTokenAuthFilter(store, ownerResolver);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void non_share_path_passes_through() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/info");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertTrue(chain.called);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void valid_token_pins_guest_identity() throws Exception {
        ShareToken t = store.create("/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), 60_000L, "label");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", VIEW_PATH);
        req.addHeader(ShareTokenAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue(chain.called);
        ShareGuestDetails d = (ShareGuestDetails) chain.capturedAuth.getDetails();
        assertEquals("/homes/admin/exec.saikudash", d.dashboardPath);
        assertEquals("admin", d.ownerUser);
        assertEquals(List.of("ROLE_ADMIN"), d.ownerRoles);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void demoted_owner_runs_under_the_live_roles_not_the_snapshot() throws Exception {
        ShareToken t = store.create("/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), 60_000L, null);
        ownerResolver.roles = List.of("ROLE_USER");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", VIEW_PATH);
        req.addHeader(ShareTokenAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        filter.doFilter(req, resp, chain);

        assertTrue(chain.called);
        ShareGuestDetails d = (ShareGuestDetails) chain.capturedAuth.getDetails();
        assertEquals(List.of("ROLE_USER"), d.ownerRoles);
    }

    @Test
    public void disabled_owner_invalidates_the_link() throws Exception {
        ShareToken t = store.create("/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), 60_000L, null);
        ownerResolver.present = false;
        MockHttpServletRequest req = new MockHttpServletRequest("GET", VIEW_PATH);
        req.addHeader(ShareTokenAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        // Same opaque body as an unknown token — a probe can't tell "stale" from "never existed".
        assertTrue(resp.getContentAsString().contains("SHARE_INVALID"));
        assertFalse("chain must NOT run for a stale link", chain.called);
    }

    @Test
    public void missing_owner_resolver_fails_closed() throws Exception {
        ShareToken t = store.create("/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), 60_000L, null);
        ShareTokenAuthFilter unwired = new ShareTokenAuthFilter(store, null);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", VIEW_PATH);
        req.addHeader(ShareTokenAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        unwired.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(chain.called);
    }

    @Test
    public void owner_resolver_throwing_fails_closed() throws Exception {
        ShareToken t = store.create("/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), 60_000L, null);
        ownerResolver.throwOnResolve = true;
        MockHttpServletRequest req = new MockHttpServletRequest("GET", VIEW_PATH);
        req.addHeader(ShareTokenAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(chain.called);
    }

    @Test
    public void revoked_token_is_invalid() throws Exception {
        ShareToken t = store.create("/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"), 60_000L, null);
        store.revoke(t.token);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", VIEW_PATH);
        req.addHeader(ShareTokenAuthFilter.TOKEN_HEADER, t.token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(chain.called);
    }

    @Test
    public void unknown_token_falls_through_to_spring() throws Exception {
        // No token presented at all — the filter stays transparent and lets the
        // intercept-url rule decide (an admin previewing the link, for instance).
        MockHttpServletRequest req = new MockHttpServletRequest("GET", VIEW_PATH);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(req, resp, chain);

        assertTrue(chain.called);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ---- helpers -------------------------------------------------------

    private static final class MutableOwnerResolver implements OwnerIdentityResolver {
        boolean present = true;
        boolean throwOnResolve = false;
        List<String> roles = List.of();

        @Override
        public OwnerIdentity resolve(String username) {
            if (throwOnResolve) {
                throw new IllegalStateException("user store unavailable");
            }
            return present ? OwnerIdentity.present(roles) : OwnerIdentity.absent();
        }
    }

    private static class TrackingChain extends MockFilterChain {
        boolean called = false;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse resp) {
            called = true;
        }
    }

    private static class ContextCapturingChain extends MockFilterChain {
        boolean called = false;
        Authentication capturedAuth;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse resp) {
            called = true;
            capturedAuth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull("the resource must see the pinned guest identity", capturedAuth);
        }
    }
}
