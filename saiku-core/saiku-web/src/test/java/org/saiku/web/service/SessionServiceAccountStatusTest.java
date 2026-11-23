/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
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

/** saiku#2432: the REAL {@link SessionService} hooks the account-status filter drives (not stubs). */
public class SessionServiceAccountStatusTest {

    private static final List<SimpleGrantedAuthority> ADMIN =
            List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static UsernamePasswordAuthenticationToken adminToken(String name) {
        return new UsernamePasswordAuthenticationToken(new User(name, "", ADMIN), null, ADMIN);
    }

    private static SessionService loggedIn(String name) {
        SessionService svc = new SessionService();
        svc.setAuthorisationPredicate(auth -> true);
        svc.setSessionRepo(new ScopedRepo());
        SecurityContextHolder.getContext().setAuthentication(adminToken(name));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(new boolean[1])));
        svc.login(request(new boolean[1]), name, "pw");
        return svc;
    }

    @Test
    public void onDemoted_refreshesTheCachedSessionRoles() {
        SessionService svc = loggedIn("boss");
        Map<?, ?> before = svc.getAllSessionObjects();
        assertEquals(true, ((List<?>) before.get("roles")).contains("ROLE_ADMIN"));

        svc.onDemoted(UsernamePasswordAuthenticationToken.authenticated(
                new User("boss", "", ADMIN), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        Map<?, ?> after = svc.getAllSessionObjects();
        assertEquals(List.of("ROLE_USER"), after.get("roles"));
    }

    @Test
    public void onDisabled_dropsTheCachedSessionAndEndsTheHttpSession() {
        SessionService svc = loggedIn("bob");
        assertFalse(svc.getAllSessionObjects().isEmpty());
        boolean[] invalidated = new boolean[1];

        svc.onDisabled(request(invalidated));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(true, invalidated[0]);
        assertNotNull(svc.getAllSessionObjects());
        assertEquals(0, svc.getAllSessionObjects().size());
    }

    private static HttpServletRequest request(boolean[] invalidated) {
        HttpSession session = (HttpSession) Proxy.newProxyInstance(
                SessionServiceAccountStatusTest.class.getClassLoader(),
                new Class<?>[] {HttpSession.class},
                (p, m, a) -> {
                    if ("invalidate".equals(m.getName())) {
                        invalidated[0] = true;
                    }
                    return "getId".equals(m.getName()) ? "sid" : defaultValue(m.getReturnType());
                });
        return (HttpServletRequest) Proxy.newProxyInstance(
                SessionServiceAccountStatusTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (p, m, a) -> "getSession".equals(m.getName()) ? session : defaultValue(m.getReturnType()));
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == void.class) return null;
        return 0;
    }
}
