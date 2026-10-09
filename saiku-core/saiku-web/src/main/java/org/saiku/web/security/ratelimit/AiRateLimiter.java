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
 *
 * <h2>Bucket storage is SHARED per limiter name (saiku#1913)</h2>
 *
 * <p>A plain {@code new AiRateLimiter(...)} in a {@code scope="request"} bean
 * gets a brand-new, empty bucket map per HTTP request, so {@link #tryAcquire}
 * always saw count = 1 and returned {@code true} — every per-endpoint limiter
 * built this way was silently disabled. Production wiring therefore uses
 * {@link #shared(String, int, long)} (also exposed as singleton Spring beans in
 * {@code saiku-beans.xml}, mirroring {@code loginRateLimiter}): every limiter
 * with the same name counts in the same store, so the budget survives across
 * requests. The bare constructors keep private, per-instance storage, which is
 * what unit tests want.
 *
 * <p>The shared store is bounded: at most {@link #MAX_TRACKED_KEYS} keys are
 * retained per limiter name. Overflow triggers a sweep of elapsed windows; if
 * the store is still over the cap (i.e. genuinely fresh high-cardinality
 * traffic) it is dropped, trading some throttling for a hard memory bound
 * rather than growing without limit in front of an anonymous, attacker-shaped
 * key space.
 */
public class AiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AiRateLimiter.class);

    /** Default budget when no explicit one is given (same property the no-arg ctor reads). */
    public static final int DEFAULT_MAX_CALLS = 30;

    /** Default fixed window, in milliseconds. */
    public static final long DEFAULT_WINDOW_MS = 60_000L;

    /**
     * Hard cap on distinct keys retained per shared limiter. An unauthenticated
     * endpoint keys on client IP, so the key space is attacker-shaped; without a
     * cap the store would grow for the life of the process.
     */
    static final int MAX_TRACKED_KEYS = 50_000;

    private static final Map<String, Map<String, Bucket>> SHARED_STORES = new ConcurrentHashMap<>();

    private final int maxCalls;
    private final long windowMs;
    private final String storeName;
    private final Map<String, Bucket> buckets;

    public AiRateLimiter() {
        this(Integer.getInteger("saiku.ai.ratelimit.maxPerMinute", DEFAULT_MAX_CALLS), DEFAULT_WINDOW_MS);
    }

    public AiRateLimiter(int maxCalls, long windowMs) {
        this.maxCalls = maxCalls;
        this.windowMs = windowMs;
        this.storeName = null; // private store: per-instance, never shared
        this.buckets = new ConcurrentHashMap<>();
    }

    private AiRateLimiter(String storeName, int maxCalls, long windowMs) {
        this.maxCalls = maxCalls;
        this.windowMs = windowMs;
        this.storeName = storeName;
        this.buckets = SHARED_STORES.computeIfAbsent(storeName, k -> new ConcurrentHashMap<>());
    }

    /**
     * A limiter whose bucket store is shared by name across every instance — the
     * production form for a limiter held in a {@code scope="request"} bean, where a
     * per-instance store would be thrown away with the request.
     *
     * <p>Two calls with the same {@code name} count against the same budget even if
     * they construct separate objects; different names stay independent.
     *
     * @param name stable limiter identity, e.g. {@code "mail.consent"} — the
     *     same string must be used everywhere the same budget is meant to apply
     * @param maxCalls calls allowed per window
     * @param windowMs window length in milliseconds
     */
    public static AiRateLimiter shared(String name, int maxCalls, long windowMs) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("shared AiRateLimiter requires a non-blank name");
        }
        return new AiRateLimiter(name, maxCalls, windowMs);
    }

    /** {@link #shared(String, int, long)} with the default budget/window. */
    public static AiRateLimiter shared(String name) {
        return sharedFromProperty(name, "saiku.ai.ratelimit.maxPerMinute", DEFAULT_MAX_CALLS);
    }

    /**
     * {@link #shared(String, int, long)} with the budget read from a system property — the shape
     * every per-endpoint limiter uses, so a deployment can widen or tighten one endpoint without
     * touching the others.
     *
     * @param name shared store name (see {@link #shared(String, int, long)})
     * @param maxPerMinuteProperty system property holding the per-window budget
     * @param defaultMaxCalls budget used when the property is unset
     */
    public static AiRateLimiter sharedFromProperty(String name, String maxPerMinuteProperty, int defaultMaxCalls) {
        return shared(name, Integer.getInteger(maxPerMinuteProperty, defaultMaxCalls), DEFAULT_WINDOW_MS);
    }

    /**
     * The shared store name, or {@code null} for a private (per-instance) limiter —
     * exposed so wiring and tests can assert which form is in use.
     */
    public String getStoreName() {
        return storeName;
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
        boundStore(now);
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

    /**
     * Keep the store bounded. Only reached when a NEW key is about to be added at
     * the cap, so it is off the hot path for normal traffic.
     */
    private void boundStore(long now) {
        if (buckets.size() < MAX_TRACKED_KEYS) return;
        buckets.entrySet().removeIf(e -> now - e.getValue().windowStartMs > windowMs);
        if (buckets.size() < MAX_TRACKED_KEYS) return;
        // Still at the cap with nothing expired: every key was minted inside the
        // current window. Dropping the store is a deliberate fail-open under
        // unbounded key cardinality — a hard memory ceiling beats an OOM, and the
        // window is short enough that real traffic re-trips the limiter quickly.
        log.warn(
                "AI rate-limit store '{}' hit {} distinct keys; dropping it to stay bounded",
                storeName,
                buckets.size());
        buckets.clear();
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
