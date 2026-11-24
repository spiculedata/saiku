/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.saiku.repository.ScopedRepo;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * saiku#2546 — the servlet session id must stay server-side.
 *
 * <p>The session cookie is {@code HttpOnly} so script can never read it, yet {@code
 * SessionService} used to put the very same id into its session map as {@code authid}, and
 * {@code GET /rest/saiku/session} handed that map back as JSON — giving any script running on
 * the origin the session id anyway. The id must remain in the internal maps (the
 * {@code httpSessionDestroyed} cleanup still matches on it) while every response handed to a
 * caller — the login response, {@link SessionService#getSession()} and {@link
 * SessionService#getAllSessionObjects()} — carries none.
 *
 * <p>Hand-rolled JDK proxies stand in for the servlet request/session (saiku-web has no
 * Mockito), following {@link SessionServiceSessionPersistenceTest}.
 */
public class SessionServiceAuthIdRedactionTest {

    private static final List<SimpleGrantedAuthority> AUTHORITIES =
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /** The whole bug: no caller-facing response may echo the servlet session id. */
    @Test
    public void login_response_and_session_readers_never_carry_authid() {
        FakeSession session = new FakeSession();
        authenticateAs("admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(session)));

        SessionService svc = newService();
        Map<String, Object> loginResponse = svc.login(request(session), "admin", "s3cret");
        assertFalse(
                "POST /rest/saiku/session must not return the servlet session id", loginResponse.containsKey("authid"));
        assertEquals("admin", loginResponse.get("username"));

        Map<String, Object> current = svc.getSession();
        assertFalse("GET /rest/saiku/session must not return the servlet session id", current.containsKey("authid"));
        assertNotNull("the SPA still keys its session off 'sessionid'", current.get("sessionid"));

        // The map every REST resource reads "username" from must be clean too.
        assertFalse(svc.getAllSessionObjects().containsKey("authid"));
        assertEquals("admin", svc.getAllSessionObjects().get("username"));
    }

    /**
     * The cleanup that keyed off the stored id must keep working: an HTTP session ending
     * (logout elsewhere, idle timeout) still forgets its principal.
     */
    @Test
    public void http_session_destroyed_still_finds_the_session_by_its_id() {
        FakeSession session = new FakeSession();
        authenticateAs("admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(session)));

        SessionService svc = newService();
        svc.login(request(session), "admin", "s3cret");
        assertEquals("admin", svc.getSession().get("username"));

        // The container ends the session: its attributes (and the map mirrored onto them) go with it.
        svc.httpSessionDestroyed("test-session-id");
        session.attributes.clear();

        assertTrue(
                "the ended HTTP session's principal must be forgotten",
                svc.getSession().isEmpty());
    }

    /** Redacting authid from the responses must not disturb the per-principal id the cleanup matches on. */
    @Test
    public void one_users_http_session_ending_does_not_forget_another_principal() {
        FakeSession alice = new FakeSession("alice-session-id");
        authenticateAs("alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(alice)));
        SessionService svc = newService();
        svc.login(request(alice), "alice", "pw");

        FakeSession bob = new FakeSession("bob-session-id");
        SecurityContextHolder.clearContext();
        authenticateAs("bob");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(bob)));
        svc.login(request(bob), "bob", "pw");

        // Alice logs out elsewhere / her session idles out.
        svc.httpSessionDestroyed("alice-session-id");
        alice.attributes.clear();

        SecurityContextHolder.clearContext();
        authenticateAs("alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(alice)));
        assertTrue("Alice's ended session must be forgotten", svc.getSession().isEmpty());

        SecurityContextHolder.clearContext();
        authenticateAs("bob");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(bob)));
        assertEquals(
                "Bob's session must survive Alice's HTTP session ending",
                "bob",
                svc.getSession().get("username"));
    }

    // ---- helpers ------------------------------------------------------

    private static SessionService newService() {
        SessionService svc = new SessionService();
        svc.setAuthenticationManager(null); // use the pre-set SecurityContext instead
        svc.setAuthorisationPredicate(auth -> true);
        svc.setSessionRepo(new ScopedRepo());
        return svc;
    }

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new User(username, "", AUTHORITIES), null, AUTHORITIES));
    }

    private static HttpServletRequest request(FakeSession session) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                SessionServiceAuthIdRedactionTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                new RequestHandler(session.proxy()));
    }

    private static final class RequestHandler implements InvocationHandler {
        private final HttpSession session;

        RequestHandler(HttpSession session) {
            this.session = session;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("getSession".equals(method.getName())) {
                return session;
            }
            return defaultValue(method.getReturnType());
        }
    }

    /** A backing-map {@link HttpSession} whose {@code getId} answers like the container does. */
    private static final class FakeSession {
        private final Map<String, Object> attributes = new HashMap<>();
        private final String id;

        FakeSession() {
            this("test-session-id");
        }

        FakeSession(String id) {
            this.id = id;
        }

        HttpSession proxy() {
            return (HttpSession) Proxy.newProxyInstance(
                    SessionServiceAuthIdRedactionTest.class.getClassLoader(),
                    new Class<?>[] {HttpSession.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getId":
                                return id;
                            case "getAttribute":
                                return attributes.get((String) args[0]);
                            case "setAttribute":
                                attributes.put((String) args[0], args[1]);
                                return null;
                            case "removeAttribute":
                                attributes.remove((String) args[0]);
                                return null;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) {
            return false;
        }
        if (t == void.class) {
            return null;
        }
        return 0;
    }
}
