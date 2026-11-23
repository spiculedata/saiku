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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    private static final String BASIC_SCHEME = "Basic";
    /** Spring's BasicAuthenticationConverter skips this many characters (scheme plus one separator). */
    private static final int BASIC_CREDENTIALS_OFFSET = 6;

    private final LoginRateLimiter limiter;

    public LoginRateLimitFilter(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String auth = req.getHeader("Authorization");
        if (auth != null && !auth.isEmpty() && isBlocked(req, auth)) {
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

    private boolean isBlocked(HttpServletRequest req, String authorization) {
        String username = basicUsername(authorization);
        if (username == null && isBasic(authorization)) return limiter.isBlockedUnattributed(req);
        return limiter.isBlocked(req, username);
    }

    /** True when Spring's {@code BasicAuthenticationConverter} would treat the header as Basic. */
    static boolean isBasic(String authorization) {
        return authorization != null
                && authorization.trim().regionMatches(true, 0, BASIC_SCHEME, 0, BASIC_SCHEME.length());
    }

    /**
     * The username of an HTTP Basic header, parsed the way {@code BasicAuthenticationConverter}
     * does (trim, case-insensitive {@code Basic} prefix, skip six characters), so no spelling
     * that authenticates escapes the per-user budgets; null when it is another scheme, malformed,
     * or has no user part.
     */
    static String basicUsername(String authorization) {
        if (!isBasic(authorization)) return null;
        String header = authorization.trim();
        if (header.length() <= BASIC_SCHEME.length()) return null;
        try {
            String decoded = new String(
                    Base64.getDecoder()
                            .decode(header.substring(BASIC_CREDENTIALS_OFFSET).getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon > 0 ? decoded.substring(0, colon) : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
