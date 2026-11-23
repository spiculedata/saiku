/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-window per-credential rate limiter for the SCIM surface (issue #1438): 100 requests per
 * minute per token, as the acceptance criteria require.
 *
 * <p>Fixed-window (not sliding) on purpose — it is what the connector docs state, so operators
 * can reason about the burst shape, and the implementation stays trivially testable with an
 * injectable window. The worst case is a 2x burst across a window boundary, which is fine for a
 * provisioning surface that an IdP drives in small batches.
 *
 * <p>Storage mirrors {@code AiRateLimiter}'s lazy-eviction {@link ConcurrentHashMap}: no
 * background thread, single-node only. A {@code null} key fails open so a wiring gap degrades to
 * "unthrottled" rather than bricking provisioning.
 */
public class ScimRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ScimRateLimiter.class);

    /** Configurable so an operator can relax it for a large bulk import. */
    public static final String MAX_PER_MINUTE_PROPERTY = "saiku.scim.rate-limit.per-minute";

    private final int maxCalls;
    private final long windowMs;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ScimRateLimiter() {
        this(Integer.getInteger(MAX_PER_MINUTE_PROPERTY, 100), 60_000L);
    }

    public ScimRateLimiter(int maxCalls, long windowMs) {
        this.maxCalls = maxCalls <= 0 ? 100 : maxCalls;
        this.windowMs = windowMs <= 0 ? 60_000L : windowMs;
    }

    /**
     * Record one call for {@code key}.
     *
     * @return {@code true} if the call may proceed; {@code false} once the key has exhausted its
     *     budget for the current window. A {@code null}/blank key is allowed through.
     */
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
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
            log.warn("SCIM rate-limit tripped key={} count={} max={}", key, b.count.get(), maxCalls);
        }
        return allowed;
    }

    /** Seconds the caller should wait before retrying; matches the fixed window. */
    public long retryAfterSeconds() {
        return Math.max(1L, windowMs / 1000L);
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
