/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.service;

import static org.junit.Assert.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
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
 * saiku#1907 (CWE-178) F3: {@link SessionService#createSession} must store TWO identities —
 * a CANONICAL (lower-cased) {@code username} for ACL/home ownership, and the ORIGINAL
 * store-spelling {@code principal} for datasource pass-through warehouse credentials.
 *
 * <p>Hand-rolled JDK proxies stand in for the servlet request/session (saiku-web has no
 * Mockito); the {@code AuthenticationManager} is left null so login uses the pre-set
 * {@link SecurityContextHolder} instead of running the real authentication filter chain.
 */
public class SessionServiceCanonicalUsernameTest {

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void login_stores_canonical_username_and_original_principal() {
        // Principal as the account store spells it ("Admin"), authenticated with ROLE_USER.
        List<SimpleGrantedAuthority> auths = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        User principal = new User("Admin", "", auths);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, auths));

        HttpServletRequest req = fakeRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        SessionService svc = new SessionService();
        svc.setAuthenticationManager(null); // skip the real filter chain; use the pre-set context
        svc.setAuthorisationPredicate(auth -> true);
        svc.setSessionRepo(new ScopedRepo());

        // The user typed "Admin" (case variant of their canonical account).
        Map<String, Object> session = svc.login(req, "Admin", "s3cret");

        assertEquals("ACL/home identity must be canonicalised (lower-cased)", "admin", session.get("username"));
        assertEquals(
                "pass-through principal must keep the original spelling the user presented",
                "Admin",
                session.get("principal"));
    }

    /**
     * saiku#1907 F3: the pass-through {@code principal} is the username AS TYPED, not the store
     * case. Store spells the account "admin"; the user types "ADMIN" — pass-through must forward
     * "ADMIN" (so a case-sensitive warehouse authenticates), while the ACL/home identity is "admin".
     */
    @Test
    public void pass_through_principal_is_the_typed_spelling_not_the_store_case() {
        List<SimpleGrantedAuthority> auths = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        User principal = new User("admin", "", auths); // store's canonical spelling
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, auths));

        HttpServletRequest req = fakeRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        SessionService svc = new SessionService();
        svc.setAuthenticationManager(null);
        svc.setAuthorisationPredicate(auth -> true);
        svc.setSessionRepo(new ScopedRepo());

        Map<String, Object> session = svc.login(req, "ADMIN", "s3cret"); // user TYPES "ADMIN"

        assertEquals("ACL/home identity is canonical", "admin", session.get("username"));
        assertEquals("pass-through principal is the typed spelling", "ADMIN", session.get("principal"));
    }

    // ---- fakes --------------------------------------------------------

    private static HttpServletRequest fakeRequest() {
        HttpSession session = (HttpSession) Proxy.newProxyInstance(
                SessionServiceCanonicalUsernameTest.class.getClassLoader(),
                new Class<?>[] {HttpSession.class},
                new SessionHandler());
        return (HttpServletRequest) Proxy.newProxyInstance(
                SessionServiceCanonicalUsernameTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                new RequestHandler(session));
    }

    private static final class RequestHandler implements InvocationHandler {
        private final HttpSession session;

        RequestHandler(HttpSession session) {
            this.session = session;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getSession".equals(name)) {
                return session;
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static final class SessionHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("getId".equals(method.getName())) {
                return "test-session-id";
            }
            return defaultValue(method.getReturnType());
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
