/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.csrf;

import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Factory for the XSRF-TOKEN cookie repository (saiku#1165 audit-3).
 *
 * <p>Spring's {@link CookieCsrfTokenRepository#withHttpOnlyFalse()} (the SPA must
 * read the cookie to echo it back as the {@code X-XSRF-TOKEN} header) does not
 * emit a {@code SameSite} attribute, leaving the CSRF cookie as the one Saiku
 * cookie without one (JSESSIONID is {@code SameSite=Lax}, the demo-gate cookie
 * {@code Strict}). Add {@code SameSite=Lax} to match JSESSIONID — defense in
 * depth; the cookie stays non-HttpOnly so the SPA double-submit pattern keeps
 * working. A {@code cookieCustomizer} can't be expressed in the security XML, so
 * this factory wires it. {@code Secure} is left to the cookie's own
 * request-driven default (now correct behind TLS via the
 * {@code ForwardedRequestCustomizer}).
 */
public final class SaikuCsrfTokenRepository {

    private SaikuCsrfTokenRepository() {}

    public static CookieCsrfTokenRepository create() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }
}
