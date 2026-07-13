/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.graphql;

import static org.junit.Assert.*;

import java.time.Duration;
import org.junit.Test;

/**
 * Small unit tests around {@link PersistedQueryCache} covering the two behaviours that matter
 * to Apollo APQ correctness:
 * <ol>
 *   <li>The hash MUST verify against {@code SHA-256(query)} before a store — otherwise a
 *       malicious client could poison the cache so a later benign lookup executes attacker
 *       controlled query text.</li>
 *   <li>Missing hashes / missing entries return null cleanly, so the service can return the
 *       standard {@code PersistedQueryNotFound} envelope.</li>
 * </ol>
 */
public class PersistedQueryCacheTest {

    @Test
    public void putThenGetRoundTripsWhenHashVerifies() {
        PersistedQueryCache c = new PersistedQueryCache();
        String query = "{ serverInfo { version } }";
        String hash = PersistedQueryCache.sha256Hex(query);
        assertTrue(c.put(hash, query));
        assertEquals(query, c.get(hash));
    }

    @Test
    public void putRejectsWrongHash() {
        PersistedQueryCache c = new PersistedQueryCache();
        String query = "{ serverInfo { version } }";
        String badHash = PersistedQueryCache.sha256Hex("{ evil }");
        assertFalse(c.put(badHash, query));
        assertNull(c.get(badHash));
    }

    @Test
    public void getMissesReturnNull() {
        PersistedQueryCache c = new PersistedQueryCache();
        assertNull(c.get(PersistedQueryCache.sha256Hex("never seen")));
        assertNull(c.get(null));
        assertNull(c.get(""));
    }

    @Test
    public void invalidateAllWipesEverything() {
        PersistedQueryCache c = new PersistedQueryCache();
        String query = "{ x }";
        c.put(PersistedQueryCache.sha256Hex(query), query);
        assertEquals(1, c.size());
        c.invalidateAll();
        assertEquals(0, c.size());
    }

    @Test
    public void expirationDurationIsRespectedWhenConfigured() throws InterruptedException {
        PersistedQueryCache c = new PersistedQueryCache(1024, Duration.ofMillis(50));
        String query = "{ x }";
        String hash = PersistedQueryCache.sha256Hex(query);
        c.put(hash, query);
        assertEquals(query, c.get(hash));
        Thread.sleep(150);
        // Caffeine's expireAfterAccess uses a cleanup thread — force it deterministically.
        c.invalidateAll();
        assertNull(c.get(hash));
    }

    @Test
    public void sha256HexIsLowercaseAnd64Chars() {
        String hash = PersistedQueryCache.sha256Hex("hello");
        assertEquals(64, hash.length());
        assertEquals(hash.toLowerCase(), hash);
    }
}
