/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.saiku.repository.ScopedRepo;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * saiku#1859 — an authenticated session must survive a restart.
 *
 * <p>The launcher persists the Jetty {@code HttpSession} to {@code saiku-home/sessions/}, Spring
 * Security's {@code SPRING_SECURITY_CONTEXT} included, so the container still considers the
 * browser logged in after a restart. Saiku, however, answered "who is this request" from a
 * per-JVM map on a singleton bean ({@code SessionService.sessionHolder}), so after a restart every
 * {@code /rest/**} request resolved to <em>no user</em>: {@code GET /rest/saiku/session} returned
 * {@code {}}, which {@code saiku-ui}'s {@code getCurrentSession()} reads as "no session" and turns
 * into the "Session ended" modal. The session map is now mirrored onto the {@link HttpSession} and
 * rehydrated on demand — these tests pin that round trip, including the two ways it must refuse.
 *
 * <p>Hand-rolled JDK proxies stand in for the servlet request/session (saiku-web has no Mockito),
 * following {@code SessionServiceCanonicalUsernameTest}. The {@code AuthenticationManager} is null
 * so login uses the pre-set {@link SecurityContextHolder} instead of the real filter chain.
 */
public class SessionServiceSessionPersistenceTest {

    private static final List<SimpleGrantedAuthority> AUTHORITIES =
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /** The map mirrored onto the HttpSession must be serialisable AND credential-free. */
    @Test
    public void login_mirrors_the_session_map_onto_the_http_session_without_the_password() {
        FakeSession session = new FakeSession();
        authenticateAs("admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(session)));

        newService().login(request(session), "admin", "s3cret");

        Object stored = session.attributes.get(SessionService.SESSION_MAP_ATTRIBUTE);
        assertNotNull("the Saiku session map must be mirrored onto the HttpSession", stored);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) stored;
        assertEquals("admin", map.get("username"));
        assertEquals(Collections.singletonList("ROLE_USER"), map.get("roles"));
        assertNull("a warehouse password must never be persisted at rest", map.get("password"));
        // It has to survive Java serialisation, which is exactly what the session store does.
        assertTrue(map instanceof java.io.Serializable);
    }

    /** The whole bug: a brand new JVM (restart) must resolve the user from the restored session. */
    @Test
    public void session_map_is_rehydrated_from_the_restored_http_session() {
        FakeSession session = new FakeSession();
        authenticateAs("admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(session)));

        newService().login(request(session), "admin", "s3cret");
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();

        // --- restart: fresh servlet request, fresh SessionService, same restored HttpSession.
        // Spring Security restores the authentication from its own persisted context.
        authenticateAs("admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(session)));
        SessionService afterRestart = newService();
        afterRestart.setSessionRepo(new ScopedRepo());

        Map<String, Object> spa = afterRestart.getSession();
        assertEquals("GET /rest/saiku/session must report the still-signed-in user", "admin", spa.get("username"));
        assertEquals(Collections.singletonList("ROLE_USER"), spa.get("roles"));
        assertNotNull("the SPA keys its resumed session off 'sessionid'", spa.get("sessionid"));
        assertFalse("never hand the stored password back out", spa.containsKey("password"));

        // This is the map every REST resource reads "username" from.
        assertEquals("admin", afterRestart.getAllSessionObjects().get("username"));
    }

    /**
     * Identity binding: a restored map whose username disagrees with the authenticated principal
     * must be discarded, never applied — otherwise one account could inherit another's home/ACL
     * scope by carrying a stale attribute.
     */
    @Test
    public void restored_map_is_discarded_when_the_identity_does_not_match_the_principal() {
        FakeSession session = new FakeSession();
        Map<String, Object> someoneElse = new HashMap<>();
        someoneElse.put("username", "victim");
        someoneElse.put("roles", new ArrayList<>(Collections.singletonList("ROLE_USER")));
        session.attributes.put(SessionService.SESSION_MAP_ATTRIBUTE, someoneElse);

        authenticateAs("attacker");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(session)));

        SessionService svc = newService();
        assertTrue(
                "a mismatched map must not resolve a session", svc.getSession().isEmpty());
        assertTrue(svc.getAllSessionObjects().isEmpty());
    }

    /** Anonymous requests are not a session: never rehydrate one for them. */
    @Test
    public void anonymous_authentication_never_resolves_a_session_map() {
        FakeSession session = new FakeSession();
        Map<String, Object> stored = new HashMap<>();
        stored.put("username", "anonymousUser");
        session.attributes.put(SessionService.SESSION_MAP_ATTRIBUTE, stored);

        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser", AUTHORITIES));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(session)));

        assertTrue(newService().getSession().isEmpty());
    }

    /** No HttpSession bound (background work, bootstrap) must stay a quiet no-op. */
    @Test
    public void works_without_a_bound_http_session() {
        authenticateAs("admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(new FakeSession())));

        SessionService svc = newService();
        svc.setSessionRepo(null);
        assertTrue(svc.getSession().isEmpty());
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
                SessionServiceSessionPersistenceTest.class.getClassLoader(),
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

    /**
     * A backing-map {@link HttpSession}. A JDK proxy (rather than a hand-written implementation)
     * keeps this clear of the deprecated {@code HttpSessionContext} method.
     */
    private static final class FakeSession {
        private final Map<String, Object> attributes = new HashMap<>();

        HttpSession proxy() {
            return (HttpSession) Proxy.newProxyInstance(
                    SessionServiceSessionPersistenceTest.class.getClassLoader(),
                    new Class<?>[] {HttpSession.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getId":
                                return "test-session-id";
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
