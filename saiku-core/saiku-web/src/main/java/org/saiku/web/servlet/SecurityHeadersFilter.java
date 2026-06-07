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
 * <p>The always-on headers (nosniff, Referrer-Policy, Permissions-Policy, and
 * HSTS under TLS) do not affect framing and are safe everywhere.
 *
 * <p><b>Frame protection is opt-in.</b> Saiku ships a cross-origin embed feature
 * ({@code ?embed=1} — the chromeless UI meant to be iframed into a
 * wiki/Confluence/Notion page, see {@code embed.svelte.ts}). A blanket
 * {@code X-Frame-Options: DENY} / {@code frame-ancestors 'none'} would break it,
 * so by default this filter emits <em>no</em> framing headers (framing behaves
 * as it did before this filter existed). Operators who do not embed — or who
 * embed only from known origins — lock it down with
 * {@code -Dsaiku.security.frameAncestors=...} :
 * <ul>
 *   <li>{@code 'none'} → no framing at all ({@code X-Frame-Options: DENY} + CSP)</li>
 *   <li>{@code 'self'} → same-origin only ({@code X-Frame-Options: SAMEORIGIN} + CSP)</li>
 *   <li>{@code 'self' https://wiki.example.com} → an allow-list (CSP {@code frame-ancestors}
 *       only; {@code X-Frame-Options} is omitted because it cannot express a list)</li>
 * </ul>
 * The dedicated public share-view endpoint sets its own {@code DENY} regardless;
 * headers here are only set when absent, so that (and the image endpoint's
 * stricter {@code default-src 'none'; sandbox} CSP) are never overridden.
 */
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse) {
            HttpServletResponse resp = (HttpServletResponse) response;
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
            // Frame protection: OPT-IN so the cross-origin ?embed=1 feature keeps
            // working by default. Configure saiku.security.frameAncestors to enable.
            String frameAncestors = frameAncestors();
            if (frameAncestors != null) {
                setIfAbsent(resp, "Content-Security-Policy", "frame-ancestors " + frameAncestors);
                String xfo = xFrameOptionsFor(frameAncestors);
                if (xfo != null) {
                    setIfAbsent(resp, "X-Frame-Options", xfo);
                }
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * The configured CSP {@code frame-ancestors} value, or {@code null} when unset
     * (the default — framing is left unrestricted so {@code ?embed=1} works).
     */
    static String frameAncestors() {
        String v = System.getProperty("saiku.security.frameAncestors");
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * Map a {@code frame-ancestors} value to the equivalent legacy
     * {@code X-Frame-Options}, or {@code null} when it can't be expressed (an
     * allow-list) — in which case only the CSP directive is sent.
     */
    static String xFrameOptionsFor(String frameAncestors) {
        if ("'none'".equals(frameAncestors)) {
            return "DENY";
        }
        if ("'self'".equals(frameAncestors)) {
            return "SAMEORIGIN";
        }
        return null;
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
