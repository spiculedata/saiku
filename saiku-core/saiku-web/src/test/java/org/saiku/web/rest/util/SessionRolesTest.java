/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1752 — locks the single authoritative role reader that every JAX-RS resource now delegates
 * to. Proves it reads {@link SecurityContextHolder} authorities and fails closed on
 * absent/unauthenticated/anonymous principals, so no reader can drift back onto the session map.
 */
public class SessionRolesTest {

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void readsAuthoritiesFromSecurityContext() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN", "ROLE_USER")));

        List<String> roles = SessionRoles.currentRoles();

        assertEquals(2, roles.size());
        assertTrue(roles.contains("ROLE_ADMIN"));
        assertTrue(roles.contains("ROLE_USER"));
    }

    @Test
    public void failsClosedWhenNoAuthentication() {
        SecurityContextHolder.clearContext();
        assertTrue(
                "absent auth must yield no roles", SessionRoles.currentRoles().isEmpty());
    }

    @Test
    public void failsClosedForAnonymousPrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertTrue(
                "anonymous principal must contribute no roles",
                SessionRoles.currentRoles().isEmpty());
    }

    @Test
    public void failsClosedWhenNotAuthenticated() {
        UsernamePasswordAuthenticationToken unauth =
                new UsernamePasswordAuthenticationToken("bob", null); // no authorities -> not authenticated
        unauth.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauth);

        assertTrue(
                "unauthenticated token must yield no roles",
                SessionRoles.currentRoles().isEmpty());
    }

    @Test
    public void guestTokenRolesResolveFromContext() {
        // Embed/share guest paths carry ROLE_*_GUEST as a SecurityContextHolder authority (saiku#941);
        // the reader must surface it exactly like any other role.
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "guest", null, AuthorityUtils.createAuthorityList("ROLE_EMBED_GUEST")));

        assertEquals(List.of("ROLE_EMBED_GUEST"), SessionRoles.currentRoles());
    }
}
