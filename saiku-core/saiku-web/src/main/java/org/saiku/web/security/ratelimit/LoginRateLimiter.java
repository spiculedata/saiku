/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-client-IP login rate limiter: after N failed attempts inside a sliding
 * window, additional attempts are blocked until the window elapses.
 *
 * <p>Storage is an in-memory {@link ConcurrentHashMap} with lazy eviction on
 * access — no background thread or {@code @Scheduled} needed. Suitable for the
 * single-node Saiku deployment; would need a shared store (Redis / DB) for a
 * horizontally-scaled setup.
 *
 * <p>Client IP resolution is conservative: {@link HttpServletRequest#getRemoteAddr()}
 * by default. Set {@code saiku.auth.trustForwardedFor=true} only if there's a
 * trusted reverse proxy in front, otherwise any client can spoof IPs via the
 * header.
 */
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final int maxAttempts;
    private final long windowMs;
    private final boolean trustForwardedFor;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter() {
        this(5, 15 * 60 * 1000L, parseTrust());
    }

    public LoginRateLimiter(int maxAttempts, long windowMs, boolean trustForwardedFor) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
        this.trustForwardedFor = trustForwardedFor;
    }

    private static boolean parseTrust() {
        return Boolean.parseBoolean(System.getProperty("saiku.auth.trustForwardedFor", "false"));
    }

    /**
     * @return true if this IP is currently locked out; false if the attempt may proceed.
     */
    public boolean isBlocked(HttpServletRequest req) {
        String ip = clientIp(req);
        if (ip == null) return false;
        Bucket b = buckets.get(ip);
        if (b == null) return false;
        long now = System.currentTimeMillis();
        // Lazy eviction: if the window elapsed since the first failure, wipe it.
        if (now - b.windowStartMs > windowMs) {
            buckets.remove(ip);
            return false;
        }
        return b.count.get() >= maxAttempts;
    }

    /** Record a failed login from this request's IP. */
    public void recordFailure(HttpServletRequest req) {
        String ip = clientIp(req);
        if (ip == null) return;
        long now = System.currentTimeMillis();
        buckets.compute(ip, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs > windowMs) {
                return new Bucket(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        Bucket b = buckets.get(ip);
        if (b != null && b.count.get() >= maxAttempts) {
            log.warn("login rate-limit tripped ip={} attempts={}", ip, b.count.get());
        }
    }

    /** Record a successful login — clears the counter so the user isn't locked out on next session. */
    public void recordSuccess(HttpServletRequest req) {
        String ip = clientIp(req);
        if (ip == null) return;
        buckets.remove(ip);
    }

    /** Exposed for testing. */
    String clientIp(HttpServletRequest req) {
        if (trustForwardedFor) {
            String fwd = req.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isEmpty()) {
                int comma = fwd.indexOf(',');
                return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
            }
        }
        return req.getRemoteAddr();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getWindowMs() {
        return windowMs;
    }

    private static final class Bucket {
        final long windowStartMs;
        final AtomicInteger count;

        Bucket(long windowStartMs, AtomicInteger count) {
            this.windowStartMs = windowStartMs;
            this.count = count;
        }
    }
}
