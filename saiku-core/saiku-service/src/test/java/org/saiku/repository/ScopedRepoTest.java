/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** {@link ScopedRepo} answers only from the current request: no process-wide fallback (#2344). */
class ScopedRepoTest {

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static HttpSession session() {
        return (HttpSession) Proxy.newProxyInstance(
                ScopedRepoTest.class.getClassLoader(), new Class<?>[] {HttpSession.class}, (p, m, a) -> null);
    }

    private static void bindRequest(HttpSession session) {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                ScopedRepoTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class}, (p, m, a) -> {
                    return "getSession".equals(m.getName()) ? session : null;
                });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void getSession_noRequestScope_returnsNull() {
        RequestContextHolder.resetRequestAttributes();

        assertNull(new ScopedRepo().getSession());
    }

    @Test
    void getSession_requestWithoutSession_returnsNull() {
        ScopedRepo repo = new ScopedRepo();
        bindRequest(session()); // another user's earlier request
        repo.getSession();
        RequestContextHolder.resetRequestAttributes();

        bindRequest(null);

        assertNull(repo.getSession(), "must not fall back to the session of an earlier request");
    }

    @Test
    void getSession_requestWithSession_returnsThatSession() {
        HttpSession mine = session();
        bindRequest(mine);

        assertSame(mine, new ScopedRepo().getSession());
    }
}
