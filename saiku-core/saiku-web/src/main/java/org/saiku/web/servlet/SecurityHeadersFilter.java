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
 * <p><b>Frame protection is ON by default (saiku#1917).</b> This filter emits
 * {@code frame-ancestors 'self'} + {@code X-Frame-Options: SAMEORIGIN} unless
 * told otherwise, so clickjacking of state-changing actions (delete,
 * share-link creation, admin toggles) is not possible out of the box. Before
 * #1917 both headers were opt-in and the SPA was frameable by any origin;
 * {@code /ui/**} is {@code security="none"} in Spring Security, so its own
 * {@code X-Frame-Options: DENY} never reached the SPA HTML.
 *
 * <p>Saiku ships a cross-origin embed feature ({@code ?embed=1} — the chromeless
 * UI meant to be iframed into a wiki/Confluence/Notion page, see
 * {@code embed.svelte.ts}). Operators who iframe it from known origins restore
 * that with an allow-list, {@code -Dsaiku.security.frameAncestors=...} :
 * <ul>
 *   <li>unset (the default) → {@code 'self'} ({@code X-Frame-Options: SAMEORIGIN} + CSP)</li>
 *   <li>{@code 'none'} → no framing at all ({@code X-Frame-Options: DENY} + CSP)</li>
 *   <li>{@code 'self' https://wiki.example.com} → an allow-list (CSP {@code frame-ancestors}
 *       only; {@code X-Frame-Options} is omitted because it cannot express a list)</li>
 *   <li>{@code off} (or {@code *}) → emit <em>no</em> framing headers at all — the
 *       pre-#1917 behaviour, for deployments that must accept an iframe from an
 *       origin that cannot be enumerated</li>
 * </ul>
 * The dedicated public share-view endpoint sets its own {@code DENY} regardless;
 * headers here are only set when absent, so that (and the image endpoint's
 * stricter {@code default-src 'none'; sandbox} CSP) are never overridden.
 */
public class SecurityHeadersFilter implements Filter {

    /** Same-origin-only framing, the default since saiku#1917. */
    static final String DEFAULT_FRAME_ANCESTORS = "'self'";

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
            // Content-Security-Policy.
            //  * An ENFORCED CSP is still opt-in via saiku.security.csp — a strict
            //    script-src on the SvelteKit + monaco (blob: workers) + ECharts SPA
            //    must be browser-validated first, so enforcing it by default could
            //    break the UI. Out of the box the documented policy ships as
            //    Content-Security-Policy-Report-Only (saiku#1917): violations are
            //    reported by the browser, nothing is blocked, so a deployment can
            //    confirm a clean console before flipping saiku.security.csp on.
            //  * Tune or silence it with saiku.security.cspReportOnly (= the
            //    default policy, or `off` to send no report-only header at all).
            String csp = prop("saiku.security.csp");
            String frameAncestors = frameAncestors();
            if (csp != null) {
                setIfAbsent(resp, "Content-Security-Policy", withFrameAncestors(csp, frameAncestors));
            } else if (frameAncestors != null) {
                setIfAbsent(resp, "Content-Security-Policy", "frame-ancestors " + frameAncestors);
            }
            // X-Frame-Options (legacy browsers) still derives from frame-ancestors.
            if (frameAncestors != null) {
                String xfo = xFrameOptionsFor(frameAncestors);
                if (xfo != null) {
                    setIfAbsent(resp, "X-Frame-Options", xfo);
                }
            }
            String cspReportOnly = cspReportOnly();
            if (cspReportOnly != null) {
                setIfAbsent(resp, "Content-Security-Policy-Report-Only", cspReportOnly);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * The enforced CSP shipped as {@code Content-Security-Policy-Report-Only} when
     * the operator has not supplied their own (saiku#1917).
     *
     * <p>Monaco needs {@code blob:} workers, ECharts renders to canvas, and SvelteKit
     * injects style tags, hence {@code 'unsafe-inline'} for styles and {@code blob:}
     * for workers. {@code app.html} carries no inline {@code <script>}, so
     * {@code script-src 'self'} is the directive that actually neutralises the
     * inline-handler payloads from the XSS findings.
     */
    static final String DEFAULT_CSP = "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob: https:; "
            + "font-src 'self' data:; "
            + "worker-src 'self' blob:; "
            + "connect-src 'self'; "
            + "object-src 'none'; "
            + "base-uri 'self'; "
            + "frame-ancestors 'self'";

    /**
     * The Content-Security-Policy-Report-Only value to send: the operator's
     * {@code saiku.security.cspReportOnly} when set, {@link #DEFAULT_CSP} by
     * default (saiku#1917), or {@code null} when explicitly disabled with
     * {@code off} / {@code none} / {@code *}.
     */
    static String cspReportOnly() {
        String v = prop("saiku.security.cspReportOnly");
        if (v == null) {
            return DEFAULT_CSP;
        }
        return isOff(v) ? null : v;
    }

    /**
     * True for the explicit opt-out tokens ({@code off} / {@code none} / {@code *}),
     * meaning "emit no header of this kind".
     */
    private static boolean isOff(String value) {
        String v = value.trim();
        return "off".equalsIgnoreCase(v) || "none".equalsIgnoreCase(v) || "*".equals(v);
    }

    /** Configured enforced full CSP, or {@code null} (default — unset so the SPA isn't broken). */
    static String csp() {
        return prop("saiku.security.csp");
    }

    private static String prop(String name) {
        String v = System.getProperty(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * The CSP {@code frame-ancestors} value: the configured
     * {@code saiku.security.frameAncestors} when set, {@code 'self'} by default
     * (saiku#1917 — the SPA is same-origin-framable out of the box), or
     * {@code null} when the operator explicitly opts out with {@code off} / {@code *}.
     */
    static String frameAncestors() {
        String v = prop("saiku.security.frameAncestors");
        if (v == null) {
            return DEFAULT_FRAME_ANCESTORS;
        }
        return isOff(v) ? null : v;
    }

    /**
     * Append {@code frame-ancestors <value>} to a full enforced CSP that omits the
     * directive, so a hand-written {@code saiku.security.csp} cannot silently
     * re-open the clickjacking hole that the #1917 default closes. An operator who
     * already wrote the directive themselves is left alone.
     */
    static String withFrameAncestors(String csp, String frameAncestors) {
        if (frameAncestors == null || containsDirective(csp, "frame-ancestors")) {
            return csp;
        }
        return csp + "; frame-ancestors " + frameAncestors;
    }

    /** True if {@code csp} already carries {@code directive} (token match, not substring). */
    private static boolean containsDirective(String csp, String directive) {
        for (String part : csp.split(";")) {
            String name = part.trim().split("\\s+", 2)[0];
            if (directive.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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
