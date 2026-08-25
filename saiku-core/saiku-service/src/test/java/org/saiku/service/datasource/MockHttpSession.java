/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.Enumeration;

public class MockHttpSession implements HttpSession {
    private Map<String, Object> attributes;

    public MockHttpSession(Map<String, Object> attributes) {
        this.attributes = attributes;
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
