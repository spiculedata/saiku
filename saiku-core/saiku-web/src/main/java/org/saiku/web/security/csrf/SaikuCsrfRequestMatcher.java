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
 * /rest/**, but exempt two cases that can't reasonably carry a CSRF token:
 *
 * <ul>
 *   <li>The session/login endpoint itself ({@code /rest/saiku/session} POST) — there
 *       is no session yet at login time, so no cookie to echo.</li>
 *   <li>Requests carrying an {@code Authorization} header (HTTP Basic / bearer) —
 *       those are stateless, not driven from a browser form, and can't be tricked
 *       into cross-site submission by an attacker.</li>
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
        return uri.startsWith("/rest/");
    }
}
