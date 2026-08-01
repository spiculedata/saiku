/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.csrf;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Decides whether CSRF protection should apply to a request.
 *
 * <p>We enforce CSRF on state-changing methods (POST/PUT/PATCH/DELETE) under
 * /rest/**, but exempt the cases that carry no ambient cookie credential for a
 * forged request to ride — so CSRF is structurally inapplicable:
 *
 * <ul>
 *   <li>The session/login endpoint itself ({@code /rest/saiku/session} POST) — there
 *       is no session yet at login time, so no cookie to echo.</li>
 *   <li>Requests carrying an {@code Authorization} header (HTTP Basic / bearer) —
 *       those are stateless, not driven from a browser form, and can't be tricked
 *       into cross-site submission by an attacker.</li>
 *   <li>Token-authenticated guest reads on the {@code <saiku-embed>} + share-link
 *       surfaces. The bundle fetches with {@code credentials:"omit"} and carries ONLY
 *       its bearer-style token header ({@code X-Saiku-Embed-Token} /
 *       {@code X-Saiku-Share-Token}) — the host page's Saiku session cookie never rides
 *       along (saiku-ui/src/embed/README.md, "Cross-origin cookie isolation"). With no
 *       ambient cookie there is nothing for CSRF to protect, exactly like the
 *       Authorization case; the token IS the sole auth carrier and a custom header can't
 *       be added to a cross-site request without a CORS preflight Saiku never grants.
 *       These guest tile-query POSTs would otherwise 403 in the CsrfFilter (which runs
 *       BEFORE embedAuthFilter, so the token is never even inspected). Gated on the
 *       token header AND scoped to the read prefixes so the session-authenticated mint /
 *       public / revoke surfaces ({@code /embed/tokens}, {@code /embed/public},
 *       {@code /share/**}) still enforce CSRF.</li>
 * </ul>
 *
 * <p>Read-only methods (GET, HEAD, OPTIONS, TRACE) are never checked.
 */
public class SaikuCsrfRequestMatcher implements RequestMatcher {

    private static final Pattern SAFE_METHODS = Pattern.compile("^(GET|HEAD|OPTIONS|TRACE)$");

    /**
     * Login endpoint — exempt from CSRF because no session cookie exists yet when
     * it's called. Matches {@code /rest/saiku/session} and {@code /rest/saiku/session/clear}.
     */
    private static final Pattern LOGIN_PATH = Pattern.compile("^/rest/saiku/session(/.*)?$");

    /** {@code EmbedAuthFilter.TOKEN_HEADER} — the sole credential on the embed guest surface. */
    private static final String EMBED_TOKEN_HEADER = "X-Saiku-Embed-Token";

    /** Embed guest READ prefixes only (query/dashboard/app) — NOT the mint/public surfaces. */
    private static final Pattern EMBED_READ_PREFIX = Pattern.compile("^/rest/saiku/api/embed/(query|dashboard|app)/.*");

    /** {@code ShareTokenAuthFilter.TOKEN_HEADER} — the sole credential on the share-view surface. */
    private static final String SHARE_TOKEN_HEADER = "X-Saiku-Share-Token";

    /** Share guest READ prefix only ({@code /share/view/**}) — NOT the mint/revoke surface. */
    private static final Pattern SHARE_VIEW_PREFIX = Pattern.compile("^/rest/saiku/share/view/.*");

    @Override
    public boolean matches(HttpServletRequest request) {
        if (SAFE_METHODS.matcher(request.getMethod()).matches()) {
            return false;
        }
        // Stateless auth (HTTP Basic / bearer) — not vulnerable to CSRF.
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isEmpty()) {
            return false;
        }
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        if (LOGIN_PATH.matcher(uri).matches()) {
            return false;
        }
        // Token-only guest reads carry no ambient cookie credential — CSRF is inapplicable.
        if (hasHeader(request, EMBED_TOKEN_HEADER)
                && EMBED_READ_PREFIX.matcher(uri).matches()) {
            return false;
        }
        if (hasHeader(request, SHARE_TOKEN_HEADER)
                && SHARE_VIEW_PREFIX.matcher(uri).matches()) {
            return false;
        }
        return uri.startsWith("/rest/");
    }

    private static boolean hasHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value != null && !value.isEmpty();
    }
}
