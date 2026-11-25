/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.saiku.repository.ScopedRepo;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2520 — {@link SessionService#runAs} must not rewrite a session-backed
 * SecurityContext.
 *
 * <p>Share/embed view paths call {@code runAs} to execute a guest's query under the share
 * owner's data scope. When the request also carries the owner-visitor's logged-in session
 * cookie, {@link SecurityContextHolder#getContext()} hands back the very object stored in the
 * HttpSession — and the old implementation called {@code setAuthentication} on it, so for the
 * duration of the delegated call the session was authorised as the owner (a concurrent
 * cookie-only request to an admin endpoint succeeded), and the session was left holding the
 * guest's identity afterwards. These tests pin the contract: the delegated identity lives in a
 * fresh context object, and the prior context OBJECT is restored untouched.
 */
public class SessionServiceRunAsTest {

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static SessionService newService() {
        SessionService svc = new SessionService();
        svc.setAuthenticationManager(null);
        svc.setAuthorisationPredicate(auth -> true);
        svc.setSessionRepo(new ScopedRepo());
        return svc;
    }

    @Test
    public void runAs_sessionBackedContext_isUntouched() {
        SessionService svc = newService();
        Authentication bobsLogin =
                new UsernamePasswordAuthenticationToken("bob", "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext sessionContext = SecurityContextHolder.createEmptyContext();
        sessionContext.setAuthentication(bobsLogin);
        SecurityContextHolder.setContext(sessionContext);
        Authentication before = sessionContext.getAuthentication();

        AtomicReference<Authentication> seenInside = new AtomicReference<>();
        AtomicReference<Authentication> sessionObjectInside = new AtomicReference<>();
        try {
            svc.runAs("owner-admin", List.of("ROLE_ADMIN"), () -> {
                // The delegated call sees the owner identity on the thread.
                seenInside.set(SecurityContextHolder.getContext().getAuthentication());
                // A "concurrent cookie-only request" reads the same session-stored object
                // while the delegated call runs — it must still see bob, never the owner.
                sessionObjectInside.set(sessionContext.getAuthentication());
                return null;
            });

            assertEquals("owner-admin", seenInside.get().getPrincipal());
            assertTrue(seenInside.get().getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
            assertEquals("bob", sessionObjectInside.get().getPrincipal());

            // The prior context object is back, byte-identical.
            assertSame(sessionContext, SecurityContextHolder.getContext());
            assertSame(before, sessionContext.getAuthentication());
            assertEquals("bob", sessionContext.getAuthentication().getPrincipal());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    public void runAs_withNoPriorContext_restoresAnEmptyContext() {
        SessionService svc = newService();
        SecurityContextHolder.clearContext();
        try {
            svc.runAs("owner-admin", List.of("ROLE_ADMIN"), () -> null);
            assertNull(
                    "the thread must be left exactly as it was — empty",
                    SecurityContextHolder.getContext().getAuthentication());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    public void runAs_actionThrowing_stillRestoresThePriorContextObject() {
        SessionService svc = newService();
        SecurityContext sessionContext = SecurityContextHolder.createEmptyContext();
        sessionContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                "bob", "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(sessionContext);
        try {
            try {
                svc.runAs("owner-admin", List.of("ROLE_ADMIN"), () -> {
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                // ignored — the point is the finally block
            }
            assertSame(sessionContext, SecurityContextHolder.getContext());
            assertEquals("bob", sessionContext.getAuthentication().getPrincipal());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
