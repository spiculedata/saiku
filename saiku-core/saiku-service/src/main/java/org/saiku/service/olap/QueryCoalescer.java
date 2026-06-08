/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-flight query coalescer (saiku#946). When a dashboard with N tiles loads
 * (or all tiles auto-refresh together), identical queries fire concurrently and
 * each re-executes the same MDX, stressing the Mondrian segment cache + the
 * connection pool. This deduplicates by an opaque key for a short window: the
 * FIRST caller for a key runs the supplier; concurrent callers for the same key
 * block and receive that same result, and the result is reused for a brief TTL.
 *
 * <p>It is NOT a result cache — it's single-flight + a tiny TTL window. Built on
 * Caffeine's {@code get(key, mappingFunction)}, which guarantees the mapping
 * function runs at most once per key under concurrency (the others wait), giving
 * coalescing for free. The key is the caller's responsibility and MUST encode
 * everything that distinguishes a result — including the user's role-set — so a
 * role-masked result is never shared across roles (the saiku#1114 hazard).
 *
 * <p>Generic + dependency-light so it's unit-testable without a live olap
 * connection. {@code enabled=false} makes {@link #coalesce} a pure pass-through.
 */
public final class QueryCoalescer {

    private static final Logger log = LoggerFactory.getLogger(QueryCoalescer.class);

    private final Cache<String, Object> cache;
    private final boolean enabled;

    /** Default: enabled, 5-second window, modest cap. */
    public QueryCoalescer() {
        this(true, Duration.ofSeconds(5), 500);
    }

    public QueryCoalescer(boolean enabled, Duration ttl, long maxSize) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl == null ? Duration.ofSeconds(5) : ttl)
                .maximumSize(maxSize)
                .build();
        log.info(
                "QueryCoalescer initialised: enabled={} ttl={} maxSize={}", enabled, ttl == null ? "5s" : ttl, maxSize);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Run {@code exec} under single-flight for {@code key}: the first caller
     * computes, concurrent callers for the same key await + share the result,
     * and it is reused for the TTL window. With coalescing disabled (or a blank
     * key), this is a direct pass-through to {@code exec}.
     *
     * <p>If {@code exec} throws, the exception propagates to all waiting callers
     * and nothing is cached (Caffeine does not retain a failed computation).
     */
    @SuppressWarnings("unchecked")
    public <T> T coalesce(String key, Supplier<T> exec) {
        if (!enabled || key == null || key.isEmpty()) {
            return exec.get();
        }
        return (T) cache.get(key, k -> exec.get());
    }

    /** Drop all coalesced entries (explicit cache clear / schema reload). */
    public void clear() {
        cache.invalidateAll();
    }

    /** Approximate number of live entries (diagnostics / tests). */
    public long size() {
        return cache.estimatedSize();
    }
}
