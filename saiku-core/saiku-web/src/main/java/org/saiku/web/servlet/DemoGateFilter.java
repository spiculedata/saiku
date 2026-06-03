/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.saiku.web.demo.DemoGate;
import org.saiku.web.demo.DemoGateCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Demo email gate (saiku#1029). Registered in {@code web.xml} <em>before</em>
 * the Spring Security {@code DelegatingFilterProxy} and mapped to
 * {@code /rest/*}, so it runs first and stays independent of Spring Security.
 *
 * <p>When the gate is enabled (demo mode + WorkOS configured), any
 * {@code /rest/saiku/**} request that isn't on the allowlist must carry a valid
 * {@code saiku_demo_verified} cookie, else it gets a {@code 401
 * {"requiresEmailGate": true}}. The allowlist is the info endpoint (so the SPA
 * can discover the gate) and the gate endpoints themselves. The login endpoint
 * ({@code /rest/saiku/session}) is intentionally NOT allowlisted — the cookie
 * gates the login form, which is the whole point.
 *
 * <p>When the gate is disabled (production, or WorkOS keys absent) the filter is
 * an immediate pass-through — zero behaviour change.
 */
public class DemoGateFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DemoGateFilter.class);
    private static final String SAIKU_PREFIX = "/rest/saiku/";

    private FilterConfig filterConfig;
    private volatile DemoGate gate; // resolved lazily from the Spring context

    @Override
    public void init(FilterConfig filterConfig) {
        this.filterConfig = filterConfig;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }

        DemoGate g = gate();
        if (g == null || !g.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = pathWithinApp(req);
        if (!requiresVerification(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (hasValidCookie(req, g.cookie())) {
            chain.doFilter(request, response);
            return;
        }

        // Not verified — block before Spring Security / the resource layer.
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write("{\"status\":\"EMAIL_GATE_REQUIRED\",\"requiresEmailGate\":true}");
    }

    /**
     * Whether a verified cookie is required for this path. Only
     * {@code /rest/saiku/**} is gated; the info + gate endpoints are
     * allowlisted so the SPA can bootstrap and clear the gate. Package-visible
     * and pure for unit tests.
     */
    static boolean requiresVerification(String path) {
        if (path == null || !path.startsWith(SAIKU_PREFIX)) return false;
        return !isAllowlisted(path);
    }

    /** Paths reachable before the gate is cleared. Package-visible for tests. */
    static boolean isAllowlisted(String path) {
        if (path.equals("/rest/saiku/info") || path.startsWith("/rest/saiku/info/")) return true;
        if (path.equals("/rest/saiku/demo/gate") || path.startsWith("/rest/saiku/demo/gate/")) return true;
        return false;
    }

    private static boolean hasValidCookie(HttpServletRequest req, DemoGateCookie cookieVerifier) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return false;
        for (Cookie c : cookies) {
            if (DemoGateCookie.COOKIE_NAME.equals(c.getName()) && cookieVerifier.verify(c.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static String pathWithinApp(HttpServletRequest req) {
        String path = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        return path;
    }

    /**
     * Lazily resolve the {@link DemoGate} bean from the root Spring context. The
     * ContextLoaderListener has already run by the time requests arrive, so the
     * context is available; we cache the bean on first hit. A missing bean (gate
     * not wired) is treated as "disabled".
     */
    private DemoGate gate() {
        DemoGate g = gate;
        if (g != null) return g;
        if (filterConfig == null) return null;
        WebApplicationContext ctx =
                WebApplicationContextUtils.getWebApplicationContext(filterConfig.getServletContext());
        if (ctx == null) return null;
        try {
            g = ctx.getBean(DemoGate.class);
            gate = g;
            return g;
        } catch (org.springframework.beans.BeansException e) {
            log.debug("DemoGate bean not present — gate disabled", e);
            return null;
        }
    }

    @Override
    public void destroy() {
        // no-op
    }
}
