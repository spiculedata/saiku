/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.graphql;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Fuzz tests for {@link PersistedQueryCache}.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@link PersistedQueryCache#put} must reject any hash that isn't {@code SHA-256(query)}.
 *       This is the load-bearing security property — an attacker who can poison the cache can
 *       return arbitrary GraphQL results to any subsequent hash-only lookup.</li>
 *   <li>{@link PersistedQueryCache#get} must never return content when the hash was refused.</li>
 *   <li>Concurrent puts / gets must not corrupt the underlying Caffeine cache.</li>
 * </ul>
 */
public class PersistedQueryCacheFuzzTest {

    private static final long SEED = 0xBADC0DE_CAFEBABEL;

    @Test
    public void putAlwaysRejectsMismatchedHashesForRandomInputs() {
        Random rng = new Random(SEED);
        PersistedQueryCache cache = new PersistedQueryCache();
        for (int i = 0; i < 5000; i++) {
            String query = GraphQlFuzzUtil.randomAscii(rng, 128);
            String wrongHash = generateWrongHash(rng, query);
            // The cache MUST reject; if it accepted, a get() would return content bound to a
            // hash the client didn't derive from that content.
            boolean stored = cache.put(wrongHash, query);
            assertFalse(
                    "cache accepted wrong hash '" + wrongHash + "' for query [" + query.length() + " chars]", stored);
            assertNull("post-reject get should miss", cache.get(wrongHash));
        }
    }

    @Test
    public void correctHashesAlwaysRoundTripForRandomInputs() {
        Random rng = new Random(SEED + 1);
        PersistedQueryCache cache = new PersistedQueryCache();
        for (int i = 0; i < 5000; i++) {
            String query = GraphQlFuzzUtil.randomAscii(rng, 128);
            String hash = PersistedQueryCache.sha256Hex(query);
            assertTrue("cache refused a valid hash — regression!", cache.put(hash, query));
            assertEquals("get returned different content than put stored", query, cache.get(hash));
        }
    }

    @Test
    public void nullAndEmptyInputsAreHandledSafely() {
        Random rng = new Random(SEED + 2);
        PersistedQueryCache cache = new PersistedQueryCache();
        for (int i = 0; i < 500; i++) {
            String s = rng.nextBoolean() ? null : (rng.nextBoolean() ? "" : GraphQlFuzzUtil.randomAscii(rng, 10));
            String hash = rng.nextBoolean() ? null : PersistedQueryCache.sha256Hex(s == null ? "" : s);
            // No throw expected on any combination.
            try {
                cache.put(hash, s);
                cache.get(hash);
            } catch (RuntimeException e) {
                fail("put/get threw on null/empty combo (hash=" + hash + ", s=" + s + "): " + e);
            }
        }
    }

    @Test
    public void concurrentPutGetIsSafe() throws InterruptedException {
        PersistedQueryCache cache = new PersistedQueryCache();
        int threads = 8;
        int iterationsPerThread = 500;
        AtomicBoolean failed = new AtomicBoolean(false);
        List<String> queries = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        Random rng = new Random(SEED + 3);
        for (int i = 0; i < 200; i++) {
            String q = GraphQlFuzzUtil.randomAscii(rng, 64);
            queries.add(q);
            hashes.add(PersistedQueryCache.sha256Hex(q));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    Random local = new Random(seed);
                    for (int i = 0; i < iterationsPerThread; i++) {
                        int idx = local.nextInt(queries.size());
                        if (local.nextBoolean()) {
                            cache.put(hashes.get(idx), queries.get(idx));
                        } else {
                            String hit = cache.get(hashes.get(idx));
                            if (hit != null && !hit.equals(queries.get(idx))) {
                                failed.set(true);
                                fail("get returned wrong content for hash " + hashes.get(idx));
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    failed.set(true);
                    fail("concurrent op threw: " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue("timed out", done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertFalse("concurrent test observed a corrupt read", failed.get());
    }

    /**
     * Build a hash that is guaranteed NOT to be {@code SHA-256(query)}. We compute a hash of a
     * different string; the odds of a collision under SHA-256 are astronomically low, but we
     * salt the input to be extra sure across all 5000 iterations.
     */
    private static String generateWrongHash(Random rng, String query) {
        String salt = "wrong-" + rng.nextInt();
        return PersistedQueryCache.sha256Hex(salt + "|" + query);
    }
}
