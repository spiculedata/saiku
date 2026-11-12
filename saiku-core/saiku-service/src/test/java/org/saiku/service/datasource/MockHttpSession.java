/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Enumeration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class MockHttpSession implements HttpSession {
    private Map<String, Object> attributes;

    public MockHttpSession(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    /**
     * Bind a request whose {@code HttpSession} is a MockHttpSession over {@code attributes} to the
     * current thread. {@code ScopedRepo} reads only the request's own session (#2344), so tests
     * that need a session must bind one; pair with {@link #unbindRequest()}.
     */
    public static MockHttpSession bindRequest(Map<String, Object> attributes) {
        MockHttpSession session = new MockHttpSession(attributes);
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                MockHttpSession.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> "getSession".equals(method.getName()) ? session : null);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return session;
    }

    public static void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Override
    public long getCreationTime() {
        return 0;
    }

    @Override
    public String getId() {
        return null;
    }

    @Override
    public long getLastAccessedTime() {
        return 0;
    }

    @Override
    public ServletContext getServletContext() {
        return null;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {}

    @Override
    public int getMaxInactiveInterval() {
        return 0;
    }

    @Override
    public Object getAttribute(String name) {
        return this.attributes.get(name);
    }

    @Override
    public Enumeration getAttributeNames() {
        return Collections.enumeration(this.attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
        this.attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {}

    @Override
    public void invalidate() {}

    @Override
    public boolean isNew() {
        return false;
    }
}
