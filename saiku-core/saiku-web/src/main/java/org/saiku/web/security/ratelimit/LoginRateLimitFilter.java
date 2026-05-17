/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Short-circuits authentication for IPs already locked out by
 * {@link LoginRateLimiter}. Wired as a Spring Security custom filter that
 * runs before {@code BASIC_AUTH_FILTER} on the main chain — once the IP
 * exceeds the failure budget, every subsequent request returns 429 until
 * the limiter's sliding window clears, without ever waking the
 * authentication provider. Issue #878.
 *
 * <p>Failure recording lives in {@link RateLimitedAuditListener}, which
 * subscribes to Spring Security's auth events and increments / clears the
 * limiter on every credential check (form, Basic, and any future provider).
 *
 * <p>Only requests that present an {@code Authorization} header are checked.
 * GETs that rely on an existing session cookie bypass the limiter — they're
 * not credential checks, and the limiter only sees one failure per cookie
 * even on a successful bcrypt-thrash attack against an active session.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter limiter;

    public LoginRateLimitFilter(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String auth = req.getHeader("Authorization");
        if (auth != null && !auth.isEmpty() && limiter.isBlocked(req)) {
            resp.setStatus(429);
            // Window expressed in seconds for Retry-After; conservative
            // upper bound is the full window length.
            resp.setHeader("Retry-After", String.valueOf(limiter.getWindowMs() / 1000L));
            resp.setContentType("application/json");
            resp.getWriter()
                    .write("{\"status\":\"RATE_LIMITED\",\"error\":\"Too many failed login attempts. "
                            + "Try again after the window resets.\"}");
            return;
        }
        chain.doFilter(req, resp);
    }
}
