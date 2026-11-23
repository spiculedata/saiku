/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.servlet;

import static org.junit.Assert.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class LegacyJspServletTest {

    private final LegacyJspServlet servlet = new LegacyJspServlet();

    @Test
    public void redirectsTheLegacyLandingPageToTheSpa() throws Exception {
        List<String> calls = new ArrayList<>();

        servlet.service(request("/index.jsp", "/saiku"), response(calls));

        assertEquals(List.of("sendRedirect:/saiku/ui/"), calls);
    }

    @Test
    public void redirectsWithAnEmptyContextPath() throws Exception {
        List<String> calls = new ArrayList<>();

        servlet.service(request("/index.jsp", ""), response(calls));

        assertEquals(List.of("sendRedirect:/ui/"), calls);
    }

    @Test
    public void answers404ForEveryOtherJsp() throws Exception {
        for (String path : new String[] {"/login.jsp", "/admin/secret.jsp", "/index.jsp.bak.jsp"}) {
            List<String> calls = new ArrayList<>();

            servlet.service(request(path, ""), response(calls));

            assertEquals(path, List.of("sendError:404"), calls);
        }
    }

    private static HttpServletRequest request(String servletPath, String contextPath) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                LegacyJspServletTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class}, (p, m, a) -> {
                    return switch (m.getName()) {
                        case "getServletPath" -> servletPath;
                        case "getContextPath" -> contextPath;
                        default -> throw new UnsupportedOperationException(m.getName());
                    };
                });
    }

    private static HttpServletResponse response(List<String> calls) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                LegacyJspServletTest.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class}, (p, m, a) -> {
                    calls.add(m.getName() + ":" + a[0]);
                    return null;
                });
    }
}
