/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
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
 * saiku#2341: the login password is kept only when a pass-through datasource needs it, outside
 * the generic session map, wiped when the session ends and replaced on re-login.
 */
public class SessionServicePassthroughSecretTest {

    private static final String PASSWORD = "s3cret-Passw0rd";
    private static final List<SimpleGrantedAuthority> AUTHORITIES =
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void login_withoutPassthroughDatasource_doesNotRetainPassword() throws Exception {
        SessionService svc = newService();
        svc.setPassthroughRequired(() -> false);

        loginAs(svc, "alice", PASSWORD);

        assertNull(svc.passthroughCredential());
        assertFalse("password reachable from SessionService state", holdsSecret(svc, PASSWORD));
    }

    @Test
    public void login_withPassthroughDatasource_retainsSecretOnlyViaAccessor() throws Exception {
        SessionService svc = newService();
        svc.setPassthroughRequired(() -> true);

        Map<String, Object> returned = loginAs(svc, "alice", PASSWORD);

        assertArrayEquals(PASSWORD.toCharArray(), svc.passthroughCredential());
        assertFalse(returned.containsKey("password"));
        assertFalse(svc.getAllSessionObjects().containsKey("password"));
        assertFalse(svc.getSession().containsKey("password"));
    }

    @Test
    public void logout_wipesSecret() throws Exception {
        SessionService svc = newService();
        loginAs(svc, "alice", PASSWORD);
        char[] held = svc.passthroughCredential();
        assertNotNull(held);

        svc.logout(request(new FakeSession()));

        char[] zeroed = new char[PASSWORD.length()];
        assertArrayEquals("the credential array must be zeroed", zeroed, held);
        assertFalse(holdsSecret(svc, PASSWORD));
    }

    @Test
    public void secondLogin_samePrincipal_replacesSecret() throws Exception {
        SessionService svc = newService();
        loginAs(svc, "alice", "first-password");
        char[] first = svc.passthroughCredential();

        loginAs(svc, "alice", "second-password");

        assertArrayEquals("second-password".toCharArray(), svc.passthroughCredential());
        assertArrayEquals("the replaced credential is wiped", new char["first-password".length()], first);
    }

    @Test
    public void httpSessionDestroyed_dropsEntryAndWipesSecret() throws Exception {
        SessionService svc = newService();
        loginAs(svc, "alice", PASSWORD);
        char[] held = svc.passthroughCredential();

        svc.httpSessionDestroyed("test-session-id");

        assertArrayEquals(new char[PASSWORD.length()], held);
        assertNull(svc.passthroughCredential());
        assertFalse(holdsSecret(svc, PASSWORD));
    }

    @Test
    public void httpSessionDestroyed_otherSessionId_keepsEntry() throws Exception {
        SessionService svc = newService();
        loginAs(svc, "alice", PASSWORD);

        svc.httpSessionDestroyed("some-other-session");

        assertArrayEquals(PASSWORD.toCharArray(), svc.passthroughCredential());
    }

    // ---- helpers ------------------------------------------------------

    private static Map<String, Object> loginAs(SessionService svc, String username, String password) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new User(username, "", AUTHORITIES), null, AUTHORITIES));
        FakeSession session = new FakeSession();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(session)));
        return svc.login(request(session), username, password);
    }

    private static SessionService newService() {
        SessionService svc = new SessionService();
        svc.setAuthenticationManager(null);
        svc.setAuthorisationPredicate(auth -> true);
        svc.setSessionRepo(new ScopedRepo());
        return svc;
    }

    /** True when any String or char[] equal to {@code secret} is reachable from the service's fields. */
    private static boolean holdsSecret(SessionService svc, String secret) throws Exception {
        for (Field f : SessionService.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (reaches(f.get(svc), secret, 0)) return true;
        }
        return false;
    }

    private static boolean reaches(Object o, String secret, int depth) {
        if (o == null || depth > 6) return false;
        if (o instanceof String s) return s.equals(secret);
        if (o instanceof char[] c) return Arrays.equals(c, secret.toCharArray());
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (reaches(e.getKey(), secret, depth + 1) || reaches(e.getValue(), secret, depth + 1)) return true;
            }
        } else if (o instanceof Collection<?> c) {
            for (Object v : c) if (reaches(v, secret, depth + 1)) return true;
        }
        return false;
    }

    private static HttpServletRequest request(FakeSession session) {
        HttpSession proxy = session.proxy();
        InvocationHandler h = (p, method, args) ->
                "getSession".equals(method.getName()) ? proxy : defaultValue(method.getReturnType());
        return (HttpServletRequest) Proxy.newProxyInstance(
                SessionServicePassthroughSecretTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                h);
    }

    private static final class FakeSession {
        private final Map<String, Object> attributes = new HashMap<>();

        HttpSession proxy() {
            return (HttpSession) Proxy.newProxyInstance(
                    SessionServicePassthroughSecretTest.class.getClassLoader(),
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
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == void.class) return null;
        return 0;
    }
}
