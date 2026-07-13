/*
 *   Copyright 2026 Spicule Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.web.graphql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Bounded cache backing Automatic Persisted Queries (APQ).
 *
 * <p>APQ is the Apollo-standard protocol that lets clients send a SHA-256 hash of the query
 * text instead of the query itself once the server has seen it:
 * <ol>
 *   <li>Client sends {@code {extensions: {persistedQuery: {version: 1, sha256Hash: "…"}}}}
 *       — no query text.</li>
 *   <li>On cache hit, server executes the cached query.</li>
 *   <li>On cache miss, server returns
 *       {@code {errors: [{message: "PersistedQueryNotFound", extensions: {code: "…"}}]}}.</li>
 *   <li>Client re-sends with the query text AND the hash. Server verifies the hash matches
 *       {@code SHA-256(queryText)}, stores it, and executes.</li>
 * </ol>
 *
 * <p>Bandwidth win for repeat queries on embedded / mobile clients. Defaults tuned for the
 * expected small Saiku deployment (1024 entries, no expiration — GraphQL queries are stable
 * per client build).
 */
public class PersistedQueryCache {

    private final Cache<String, String> cache;

    public PersistedQueryCache() {
        this(1024, Duration.ZERO);
    }

    /**
     * @param maxSize hard entry cap; oldest entries evicted first
     * @param expireAfterAccess non-zero to auto-evict cold entries; use {@link Duration#ZERO} to disable
     */
    public PersistedQueryCache(long maxSize, Duration expireAfterAccess) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(maxSize);
        if (expireAfterAccess != null && !expireAfterAccess.isZero() && !expireAfterAccess.isNegative()) {
            builder = builder.expireAfterAccess(expireAfterAccess);
        }
        this.cache = builder.build();
    }

    /** @return the cached query text for the given SHA-256 hash, or {@code null} on miss. */
    public String get(String sha256Hash) {
        if (sha256Hash == null || sha256Hash.isBlank()) return null;
        return cache.getIfPresent(sha256Hash);
    }

    /**
     * Store the query only if {@code SHA-256(queryText) == sha256Hash}. The hash check is
     * mandatory — otherwise a client could poison the cache with arbitrary bindings.
     *
     * @return {@code true} if stored, {@code false} if the hash didn't verify.
     */
    public boolean put(String sha256Hash, String queryText) {
        if (sha256Hash == null || queryText == null) return false;
        String computed = sha256Hex(queryText);
        if (!computed.equalsIgnoreCase(sha256Hash)) {
            return false;
        }
        cache.put(sha256Hash, queryText);
        return true;
    }

    /** Wipe the entire cache — call on schema changes so stale persisted queries can't return unexpected shapes. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    public long size() {
        return cache.estimatedSize();
    }

    /** SHA-256 hex digest of the input using UTF-8 bytes. Lowercase hex output. */
    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM — should not happen", e);
        }
    }
}
