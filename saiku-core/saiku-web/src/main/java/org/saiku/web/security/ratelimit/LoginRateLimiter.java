/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.saiku.service.util.security.Usernames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Login rate limiter keyed on <em>who</em> is trying and <em>from where</em>:
 * after N failed attempts inside a sliding window, further attempts are blocked
 * until the window elapses (saiku#2339).
 *
 * <p>Three independent budgets, any of which blocks:
 * <ul>
 *   <li>(client IP, username): {@code maxAttempts} (default 5), so one user's typos
 *       do not lock out colleagues who share a NAT or proxy address;
 *   <li>username alone: {@code maxUserAttempts} (default 20), which still catches a
 *       distributed attack on one account;
 *   <li>client IP alone: {@code maxIpAttempts} (default 50), a high-water mark for
 *       enumeration across many usernames. Attempts that carry no username (for example
 *       a bearer token) count only against this bucket.
 * </ul>
 *
 * <p>Storage is an in-memory {@link ConcurrentHashMap} with lazy eviction on
 * access, no background thread. Suitable for the single-node Saiku deployment;
 * a horizontally-scaled setup would need a shared store.
 *
 * <p>Client IP resolution is conservative: {@link HttpServletRequest#getRemoteAddr()}
 * unless {@code saiku.auth.trustedProxyHops=<n>} (n greater than 0) says that the
 * last <em>n</em> hops are trusted proxies, counting the immediate peer. With {@code n}
 * the client is the {@code n}-th entry from the right of {@code X-Forwarded-For}
 * (the entries to its left are attacker-controlled and never used). With fewer than
 * {@code n} entries the peer address is used. The legacy
 * {@code saiku.auth.trustForwardedFor=true} is an alias for {@code n=1}.
 */
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final long DEFAULT_WINDOW_MS = 15 * 60 * 1000L;
    static final int DEFAULT_MAX_USER_ATTEMPTS = 20;
    static final int DEFAULT_MAX_IP_ATTEMPTS = 50;
    private static final String KEY_SEPARATOR = "\u0000";

    private final int maxAttempts;
    private final long windowMs;
    private final int trustedProxyHops;
    private final int maxUserAttempts;
    private final int maxIpAttempts;
    private final Map<String, Bucket> ipUserBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> userBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    public LoginRateLimiter() {
        this(
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_WINDOW_MS,
                parseTrustedProxyHops(),
                DEFAULT_MAX_USER_ATTEMPTS,
                DEFAULT_MAX_IP_ATTEMPTS);
    }

    /** @param trustForwardedFor legacy switch: true means one trusted proxy hop. */
    public LoginRateLimiter(int maxAttempts, long windowMs, boolean trustForwardedFor) {
        this(maxAttempts, windowMs, trustForwardedFor ? 1 : 0, DEFAULT_MAX_USER_ATTEMPTS, DEFAULT_MAX_IP_ATTEMPTS);
    }

    public LoginRateLimiter(
            int maxAttempts, long windowMs, int trustedProxyHops, int maxUserAttempts, int maxIpAttempts) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
        this.trustedProxyHops = Math.max(0, trustedProxyHops);
        this.maxUserAttempts = maxUserAttempts;
        this.maxIpAttempts = maxIpAttempts;
    }

    private static int parseTrustedProxyHops() {
        String configured = System.getProperty("saiku.auth.trustedProxyHops");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException e) {
                log.warn(
                        "ignoring invalid saiku.auth.trustedProxyHops='{}'; forwarded headers are not trusted",
                        configured);
                return 0;
            }
        }
        return Boolean.parseBoolean(System.getProperty("saiku.auth.trustForwardedFor", "false")) ? 1 : 0;
    }

    /**
     * @return true if this client is currently locked out for this username; false if the attempt may proceed.
     */
    public boolean isBlocked(HttpServletRequest req, String username) {
        String ip = clientIp(req);
        if (ip == null) return false;
        String user = userKey(username);
        if (overBudget(ipBuckets, ip, maxIpAttempts)) return true;
        if (user == null) return false;
        return overBudget(ipUserBuckets, ip + KEY_SEPARATOR + user, maxAttempts)
                || overBudget(userBuckets, user, maxUserAttempts);
    }

    /** Record a failed login from this request's client for this username. */
    public void recordFailure(HttpServletRequest req, String username) {
        String ip = clientIp(req);
        if (ip == null) return;
        String user = userKey(username);
        long now = System.currentTimeMillis();
        int ipCount = increment(ipBuckets, ip, now);
        if (user == null) {
            if (ipCount >= maxIpAttempts) log.warn("login rate-limit tripped ip={} attempts={}", ip, ipCount);
            return;
        }
        int pairCount = increment(ipUserBuckets, ip + KEY_SEPARATOR + user, now);
        int userCount = increment(userBuckets, user, now);
        if (pairCount >= maxAttempts || userCount >= maxUserAttempts || ipCount >= maxIpAttempts) {
            log.warn(
                    "login rate-limit tripped ip={} user={} ipUserAttempts={} userAttempts={} ipAttempts={}",
                    ip,
                    user,
                    pairCount,
                    userCount,
                    ipCount);
        }
    }

    /** Record a successful login: clears the (IP, user) and per-user counters so the user is not locked out next session. */
    public void recordSuccess(HttpServletRequest req, String username) {
        String ip = clientIp(req);
        String user = userKey(username);
        if (ip == null || user == null) return;
        ipUserBuckets.remove(ip + KEY_SEPARATOR + user);
        userBuckets.remove(user);
    }

    private boolean overBudget(Map<String, Bucket> buckets, String key, int max) {
        Bucket b = buckets.get(key);
        if (b == null) return false;
        long now = System.currentTimeMillis();
        // Lazy eviction: if the window elapsed since the first failure, wipe it.
        if (now - b.windowStartMs > windowMs) {
            buckets.remove(key);
            return false;
        }
        return b.count.get() >= max;
    }

    private int increment(Map<String, Bucket> buckets, String key, long now) {
        return buckets.compute(key, (k, existing) -> {
                    if (existing == null || now - existing.windowStartMs > windowMs) {
                        return new Bucket(now, new AtomicInteger(1));
                    }
                    existing.count.incrementAndGet();
                    return existing;
                })
                .count
                .get();
    }

    private static String userKey(String username) {
        if (username == null || username.isBlank()) return null;
        return Usernames.canonicalize(username.trim());
    }

    /** Exposed for testing. */
    String clientIp(HttpServletRequest req) {
        if (trustedProxyHops > 0) {
            String fwd = req.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                List<String> hops = new ArrayList<>();
                for (String part : fwd.split(",")) {
                    if (!part.isBlank()) hops.add(part.trim());
                }
                if (hops.size() >= trustedProxyHops) {
                    return hops.get(hops.size() - trustedProxyHops);
                }
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
