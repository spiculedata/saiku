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
import org.saiku.web.security.RequestPaths;

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
            // saiku#2440: the dashboard plugin frame bootstrap carries its OWN policy
            // instead of the shell's. A network-loaded frame does not inherit its
            // parent's CSP (an srcdoc frame does, and the shell's script-src blocks the
            // plugin), so this response is what sandboxes the plugin: no operator shell
            // CSP and no report-only shell policy, just PLUGIN_FRAME_CSP + frame-ancestors.
            if (isPluginFrame(request)) {
                String frameAncestors = pluginFrameAncestors(frameAncestors());
                resp.setHeader("Content-Security-Policy", pluginFrameCsp(frameAncestors));
                if (frameAncestors != null) {
                    String xfo = xFrameOptionsFor(frameAncestors);
                    if (xfo != null) {
                        setIfAbsent(resp, "X-Frame-Options", xfo);
                    }
                }
                chain.doFilter(request, response);
                return;
            }
            // Content-Security-Policy.
            //  * The SPA shell (/ui/**) and the hash-pinned demo pages enforce their own
            //    script-src through a <meta> policy baked into the HTML (SvelteKit hash mode,
            //    saiku#1917). A header policy would intersect with it and block the inline
            //    bootstrap, so by default they get only frame-ancestors.
            //  * Every other response gets an enforced NON_SPA_CSP by default (saiku#2430):
            //    nothing there needs an inline script, so script-src 'self' is safe.
            //  * saiku.security.csp, when set, is enforced on everything (tighten only for the
            //    shell: its meta policy still applies).
            String csp = prop("saiku.security.csp");
            String frameAncestors = frameAncestors();
            if (csp == null && !isSpaShell(request)) {
                csp = NON_SPA_CSP;
            }
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
     * The policy enforced by default on responses outside the SPA shell (saiku#2430).
     * No inline script runs on those responses (share views and the image endpoint set
     * their own stricter policy, which wins), so {@code script-src 'self'} neutralises
     * injected handlers without touching the SPA.
     *
     * <p>Since saiku#2528 the policy is also sealed against the HTML export surface:
     * {@code default-src 'none'} denies everything not named explicitly, {@code img-src}
     * keeps a rendered cell property from leaking values to an external host (the export
     * page only ever loads self-hosted arrow images and {@code data:} URIs),
     * {@code style-src 'unsafe-inline'} permits the export page's inlined stylesheet and
     * the cell {@code style} property, and {@code form-action 'self'} stops an injected
     * element from posting credentials off-origin.
     */
    static final String NON_SPA_CSP = "default-src 'none'; "
            + "script-src 'self'; "
            + "object-src 'none'; "
            + "base-uri 'self'; "
            + "img-src 'self' data:; "
            + "style-src 'unsafe-inline'; "
            + "form-action 'self'";

    /** Path prefixes whose HTML carries its own hash-pinned {@code <meta>} policy. */
    private static final String[] META_POLICY_PREFIXES = {"/ui/", "/aqvira-demo/", "/embed-demo/"};

    /**
     * True for the SPA shell and the demo pages: HTML that pins its inline bootstrap
     * script by hash in a {@code <meta>} policy, which a header {@code script-src}
     * would block. Decided on the decoded path ({@link RequestPaths}, #2342).
     */
    static boolean isSpaShell(ServletRequest request) {
        if (!(request instanceof HttpServletRequest)) {
            return false;
        }
        String path = RequestPaths.pathWithinApplication((HttpServletRequest) request);
        if (path == null) {
            return false;
        }
        if (path.equals("/ui") || path.equals("/") || path.equals("/index.html")) {
            return true;
        }
        for (String prefix : META_POLICY_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Path of the static plugin frame bootstrap ({@code saiku-ui/static/plugin-frame.html}). */
    static final String PLUGIN_FRAME_PATH = "/ui/plugin-frame.html";

    /**
     * The enforced policy of the dashboard plugin frame bootstrap (saiku#2440); mirrors
     * {@code PLUGIN_FRAME_CSP} in {@code saiku-ui/src/lib/dashboard/custom/pluginBridge.ts}.
     *
     * <p>The frame runs admin-installed plugin code, which is inline script by design
     * ({@code docs/APP-BUILDER-PLUGINS.md}), so {@code script-src 'unsafe-inline'} is the point.
     * {@code sandbox allow-scripts} (no {@code allow-same-origin}) gives the document an opaque
     * origin even when it is loaded without the tile's sandboxed iframe (opened top-level, or
     * framed without a {@code sandbox} attribute). That holds for every spelling of the path the
     * container resolves to this file, because {@link #isPluginFrame} decides on the decoded path.
     * Everything else is denied: no remote scripts, styles, fonts, frames or workers, no
     * fetch/XHR/WebSocket ({@code connect-src 'none'}; the data arrives over postMessage), no
     * {@code <base>}, no form posts. The file itself is static and never reflects request input:
     * the host posts the plugin document into it.
     */
    static final String PLUGIN_FRAME_CSP = "sandbox allow-scripts; "
            + "default-src 'none'; "
            + "script-src 'unsafe-inline'; "
            + "style-src 'unsafe-inline'; "
            + "img-src data:; "
            + "connect-src 'none'; "
            + "base-uri 'none'; "
            + "form-action 'none'";

    /** The plugin frame policy, with {@code frame-ancestors} when one applies. */
    static String pluginFrameCsp(String frameAncestors) {
        return frameAncestors == null ? PLUGIN_FRAME_CSP : PLUGIN_FRAME_CSP + "; frame-ancestors " + frameAncestors;
    }

    /**
     * The {@code frame-ancestors} for the plugin frame, derived from the UI's value
     * ({@link #frameAncestors()}). The frame's parent is always the Saiku SPA, so {@code 'self'}
     * is always admitted: {@code 'none'} becomes {@code 'self'} and an allow-list without it gets
     * it prepended. Otherwise the tile would be refused inside Saiku's own pages. This does not
     * make the SPA more frameable: the SPA keeps its own value, and {@code frame-ancestors}
     * checks every ancestor, so a page the SPA may not be framed by still cannot reach the
     * plugin frame through it. {@code null} (framing protection {@code off}) stays {@code null}.
     */
    static String pluginFrameAncestors(String frameAncestors) {
        if (frameAncestors == null) {
            return null;
        }
        String value = frameAncestors.trim();
        if (value.isEmpty() || "'none'".equalsIgnoreCase(value)) {
            return DEFAULT_FRAME_ANCESTORS;
        }
        for (String source : value.split("\\s+")) {
            if ("'self'".equalsIgnoreCase(source)) {
                return value;
            }
        }
        return DEFAULT_FRAME_ANCESTORS + " " + value;
    }

    /**
     * True for a request of {@link #PLUGIN_FRAME_PATH} under the webapp context, on the decoded
     * path the container serves the file from ({@link RequestPaths#pathWithinApplication}, #2342),
     * so percent-encoded spellings ({@code /ui/%70lugin-frame.html}) get the frame policy too.
     * {@link RequestPaths} leaves a percent-encoded context path in place
     * ({@code /%73aiku/ui/plugin-frame.html}), so the context path is also stripped from the
     * decoded form. Over-matching is harmless here: this policy is stricter than the shell's.
     */
    static boolean isPluginFrame(ServletRequest request) {
        if (!(request instanceof HttpServletRequest)) {
            return false;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        String path = RequestPaths.pathWithinApplication(req);
        if (path == null) {
            return false;
        }
        if (PLUGIN_FRAME_PATH.equals(path)) {
            return true;
        }
        String context = req.getContextPath();
        return context != null && !context.isEmpty() && path.equals(context + PLUGIN_FRAME_PATH);
    }

    /**
     * The {@code Content-Security-Policy-Report-Only} value to send: the operator's
     * {@code saiku.security.cspReportOnly} when set, otherwise {@code null}. There is
     * no default report-only policy since saiku#2430: the SPA enforces its own policy
     * from a {@code <meta>} tag and a default report-only header reported the inline
     * SvelteKit bootstrap on every page load. {@code off} / {@code none} / {@code *}
     * also yield {@code null}.
     */
    static String cspReportOnly() {
        String v = prop("saiku.security.cspReportOnly");
        return (v == null || isOff(v)) ? null : v;
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
