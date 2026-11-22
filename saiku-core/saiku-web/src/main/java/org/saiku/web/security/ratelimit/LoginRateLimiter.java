/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;
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
 * <p>Storage is an in-memory Caffeine {@link Cache} per budget: a bucket expires one
 * window after its <em>last</em> recorded failure whether or not the key is looked up
 * again, and each map is capped at {@link #MAX_BUCKETS} entries (saiku#2339, bounded by
 * saiku#2426 — keys were attacker-supplied and previously unbounded in both count and
 * length). No background thread. Suitable for the single-node Saiku deployment; a
 * horizontally-scaled setup would need a shared store.
 *
 * <p>Client IP resolution is {@link ClientIpResolver}'s (shared with the audit log): the TCP
 * peer unless {@code saiku.auth.trustedProxyHops=<n>} says that the last <em>n</em> hops are
 * trusted proxies, counting the immediate peer.
 *
 * <p>A success clears only the (IP, username) bucket: the per-username budget is a defence
 * against distributed guessing, so one legitimate login must not keep resetting it.
 */
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final long DEFAULT_WINDOW_MS = 15 * 60 * 1000L;
    static final int DEFAULT_MAX_USER_ATTEMPTS = 20;
    static final int DEFAULT_MAX_IP_ATTEMPTS = 50;
    static final String MAX_USER_ATTEMPTS_PROPERTY = "saiku.auth.maxAttemptsPerUser";
    static final String MAX_IP_ATTEMPTS_PROPERTY = "saiku.auth.maxAttemptsPerIp";
    /** Upper bound on the canonicalised username used as a (partial) bucket key (saiku#2426). */
    static final int MAX_USERNAME_LENGTH = 256;
    /** Upper bound on the number of buckets held per map (saiku#2426). */
    static final long MAX_BUCKETS = 10_000L;

    private static final String KEY_SEPARATOR = "\u0000";

    private final int maxAttempts;
    private final long windowMs;
    private final int trustedProxyHops;
    private final int maxUserAttempts;
    private final int maxIpAttempts;
    private final Cache<String, Bucket> ipUserBuckets;
    private final Cache<String, Bucket> userBuckets;
    private final Cache<String, Bucket> ipBuckets;

    private static Cache<String, Bucket> newBuckets(long windowMs, Ticker ticker) {
        return Caffeine.newBuilder()
                .ticker(ticker)
                .maximumSize(MAX_BUCKETS)
                .expireAfterWrite(windowMs, TimeUnit.MILLISECONDS)
                .build();
    }

    public LoginRateLimiter() {
        this(
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_WINDOW_MS,
                ClientIpResolver.configuredTrustedProxyHops(),
                positiveIntProperty(MAX_USER_ATTEMPTS_PROPERTY, DEFAULT_MAX_USER_ATTEMPTS),
                positiveIntProperty(MAX_IP_ATTEMPTS_PROPERTY, DEFAULT_MAX_IP_ATTEMPTS));
    }

    /**
     * A positive integer system property, or {@code fallback} (with a WARN) when it is set but
     * not a positive integer.
     */
    static int positiveIntProperty(String name, int fallback) {
        String configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(configured.trim());
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // falls through to the warning below
        }
        log.warn("ignoring invalid {}='{}'; using the default {}", name, configured, fallback);
        return fallback;
    }

    /** @param trustForwardedFor legacy switch: true means one trusted proxy hop. */
    public LoginRateLimiter(int maxAttempts, long windowMs, boolean trustForwardedFor) {
        this(maxAttempts, windowMs, trustForwardedFor ? 1 : 0, DEFAULT_MAX_USER_ATTEMPTS, DEFAULT_MAX_IP_ATTEMPTS);
    }

    public LoginRateLimiter(
            int maxAttempts, long windowMs, int trustedProxyHops, int maxUserAttempts, int maxIpAttempts) {
        this(maxAttempts, windowMs, trustedProxyHops, maxUserAttempts, maxIpAttempts, Ticker.systemTicker());
    }

    /** @param ticker the time source, injectable so tests need no sleeping */
    LoginRateLimiter(
            int maxAttempts,
            long windowMs,
            int trustedProxyHops,
            int maxUserAttempts,
            int maxIpAttempts,
            Ticker ticker) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
        this.trustedProxyHops = Math.max(0, trustedProxyHops);
        this.maxUserAttempts = maxUserAttempts;
        this.maxIpAttempts = maxIpAttempts;
        this.ipUserBuckets = newBuckets(windowMs, ticker);
        this.userBuckets = newBuckets(windowMs, ticker);
        this.ipBuckets = newBuckets(windowMs, ticker);
    }

    /**
     * Blocked requests are rejected here without recording another failure, so an
     * already-blocked bucket is never renewed and always expires one window after the
     * last recorded failure.
     *
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

    /**
     * Strict rule for credentials the limiter cannot attribute to a username (for example Basic
     * credentials with no user part): the per-address budget is {@code maxAttempts}, not the
     * high-water mark, since nothing narrower than the address identifies the caller.
     */
    public boolean isBlockedUnattributed(HttpServletRequest req) {
        String ip = clientIp(req);
        return ip != null && overBudget(ipBuckets, ip, maxAttempts);
    }

    /** Record a failed login from this request's client for this username. */
    public void recordFailure(HttpServletRequest req, String username) {
        String ip = clientIp(req);
        if (ip == null) return;
        String user = userKey(username);
        int ipCount = increment(ipBuckets, ip);
        if (user == null) {
            if (ipCount >= maxIpAttempts) log.warn("login rate-limit tripped ip={} attempts={}", ip, ipCount);
            return;
        }
        int pairCount = increment(ipUserBuckets, ip + KEY_SEPARATOR + user);
        int userCount = increment(userBuckets, user);
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

    /**
     * Record a successful login: clears the (IP, user) counter so the user is not locked out next
     * session. The per-user budget is left to expire, or one success would keep resetting it.
     */
    public void recordSuccess(HttpServletRequest req, String username) {
        String ip = clientIp(req);
        String user = userKey(username);
        if (ip == null || user == null) return;
        ipUserBuckets.invalidate(ip + KEY_SEPARATOR + user);
    }

    private boolean overBudget(Cache<String, Bucket> buckets, String key, int max) {
        // Caffeine treats an expired entry as absent, so a window elapsed since the
        // last failure no longer blocks — even before the entry is evicted.
        Bucket b = buckets.getIfPresent(key);
        return b != null && b.count.get() >= max;
    }

    private int increment(Cache<String, Bucket> buckets, String key) {
        Bucket b = buckets.asMap().compute(key, (k, existing) -> {
            if (existing == null) {
                return new Bucket(1);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return b == null ? 0 : b.count.get();
    }

    /**
     * Canonicalised, length-clamped username for use as a bucket key. The clamp (saiku#2426)
     * caps per-key memory regardless of what a caller passes in; two oversized usernames
     * sharing a prefix share one bucket, which for a rate limiter is fail-safe.
     */
    private static String userKey(String username) {
        if (username == null || username.isBlank()) return null;
        String canonical = Usernames.canonicalize(username.trim());
        return canonical.length() <= MAX_USERNAME_LENGTH ? canonical : canonical.substring(0, MAX_USERNAME_LENGTH);
    }

    /** Exposed for testing. */
    String clientIp(HttpServletRequest req) {
        return ClientIpResolver.resolve(req, trustedProxyHops);
    }

    /** Exposed for testing: forces pending evictions, then the approximate size of each bucket map. */
    long[] bucketCountsForTesting() {
        ipUserBuckets.cleanUp();
        userBuckets.cleanUp();
        ipBuckets.cleanUp();
        return new long[] {ipUserBuckets.estimatedSize(), userBuckets.estimatedSize(), ipBuckets.estimatedSize()};
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getWindowMs() {
        return windowMs;
    }

    /** One failure counter; expires one window after its last failure (saiku#2426). */
    private static final class Bucket {
        final AtomicInteger count;

        Bucket(int initial) {
            this.count = new AtomicInteger(initial);
        }
    }
}
