/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-window call-rate limiter for the cost-bearing AI ask endpoint
 * (saiku#1151). Each natural-language ask reaches a paid LLM provider, so an
 * unbounded call frequency is a direct cost-DoS. After {@code maxCalls} inside
 * a {@code windowMs} window, further calls for that key are rejected until the
 * window rolls over.
 *
 * <p>Deliberately servlet-free and key-string based: the caller derives the
 * key (principal + client IP) and this class just counts. That keeps it
 * trivially unit-testable and mirrors {@link LoginRateLimiter}'s lazy-eviction
 * {@link ConcurrentHashMap} storage — no background thread, single-node only.
 */
public class AiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AiRateLimiter.class);

    private final int maxCalls;
    private final long windowMs;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public AiRateLimiter() {
        this(Integer.getInteger("saiku.ai.ratelimit.maxPerMinute", 30), 60_000L);
    }

    public AiRateLimiter(int maxCalls, long windowMs) {
        this.maxCalls = maxCalls;
        this.windowMs = windowMs;
    }

    /**
     * Record one call for {@code key} and report whether it is within budget.
     *
     * @return {@code true} if the call may proceed; {@code false} if this key
     *     has exhausted its budget for the current window. A {@code null} key
     *     (identity could not be derived) is allowed through — fail-open here so
     *     a wiring gap never bricks the feature; identity derivation is the
     *     caller's responsibility.
     */
    public boolean tryAcquire(String key) {
        if (key == null) return true;
        long now = System.currentTimeMillis();
        Bucket b = buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs > windowMs) {
                return new Bucket(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        boolean allowed = b.count.get() <= maxCalls;
        if (!allowed) {
            log.warn("AI ask rate-limit tripped key={} count={} max={}", key, b.count.get(), maxCalls);
        }
        return allowed;
    }

    public int getMaxCalls() {
        return maxCalls;
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
