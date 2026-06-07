/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.csrf;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces the deferred {@link CsrfToken} to be resolved on every response, so the
 * {@code XSRF-TOKEN} cookie is written and readable from JavaScript before the
 * SPA fires its next mutating request.
 *
 * <p>Spring Security 6's default {@code CsrfTokenRequestAttributeHandler} stores the
 * token as a {@code Supplier<CsrfToken>} attribute that's only materialised when
 * something calls {@code getToken()} — typically the {@code CsrfFilter} on a
 * state-changing request. That's the wrong order for an SPA: the first POST after
 * login needs the cookie to ALREADY be present so the JS can echo it as
 * {@code X-XSRF-TOKEN}. Without this filter, the SPA's first state-changing call
 * would always 403 ("no token to compare against").
 *
 * <p>Wired ahead of the security filter chain in {@code applicationContext-saiku.xml}
 * so every response (including the login response) carries the cookie.
 */
public class CsrfCookieMaterializerFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Resolve the token BEFORE the chain runs. By the time the servlet
        // returns the response is committed, and adding a Set-Cookie header
        // then is a no-op. Calling getToken() triggers the deferred supplier,
        // which calls CookieCsrfTokenRepository.saveToken() — that writes the
        // Set-Cookie header to the (still-uncommitted) response. CsrfFilter
        // runs upstream of this filter and has already installed the
        // SupplierCsrfToken attribute, so by the time we read it here it's
        // present and live.
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
