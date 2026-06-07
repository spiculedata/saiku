/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Stamps browser-facing security response headers on every response (saiku#1165
 * hardening).
 *
 * <p>Registered in {@code web.xml} mapped to {@code /*} and listed first, so it
 * also covers the SPA surface ({@code /ui/**}, {@code /}) and static assets —
 * those Spring Security chains are {@code security="none"}, so Spring's own
 * header support never runs for them. A plain servlet filter is the only thing
 * that reaches every response uniformly.
 *
 * <p>The CSP here is intentionally limited to {@code frame-ancestors 'none'}
 * (anti-clickjacking) and does <b>not</b> constrain content sources — a full
 * {@code script-src}/{@code style-src} policy would break the SvelteKit + monaco
 * + ECharts SPA (inline styles, eval) and needs a separate report-only rollout.
 * Headers are only set when absent, so endpoints that deliberately send a
 * stricter policy (e.g. the image-serving endpoint's {@code default-src 'none';
 * sandbox}) are not overridden.
 */
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse) {
            HttpServletResponse resp = (HttpServletResponse) response;
            // Clickjacking: refuse to be framed (legacy header + modern CSP directive).
            setIfAbsent(resp, "X-Frame-Options", "DENY");
            setIfAbsent(resp, "Content-Security-Policy", "frame-ancestors 'none'");
            // Stop MIME-sniffing of responses (e.g. branding / uploaded content).
            setIfAbsent(resp, "X-Content-Type-Options", "nosniff");
            // Don't leak dashboard/query URLs to third parties via Referer.
            setIfAbsent(resp, "Referrer-Policy", "no-referrer");
            // Lock down powerful browser features the app never uses.
            setIfAbsent(resp, "Permissions-Policy", "geolocation=(), camera=(), microphone=()");
            // HSTS only when the request actually arrived over TLS (directly, or
            // via a trusted reverse proxy that set X-Forwarded-Proto) — never on
            // plain-HTTP dev, where it would be wrong/harmful.
            if (isSecure(request)) {
                setIfAbsent(resp, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
        }
        chain.doFilter(request, response);
    }

    private static void setIfAbsent(HttpServletResponse resp, String name, String value) {
        if (!resp.containsHeader(name)) {
            resp.setHeader(name, value);
        }
    }

    private static boolean isSecure(ServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        if (request instanceof HttpServletRequest) {
            String proto = ((HttpServletRequest) request).getHeader("X-Forwarded-Proto");
            return "https".equalsIgnoreCase(proto);
        }
        return false;
    }
}
