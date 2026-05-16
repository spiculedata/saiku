/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.cache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.cache.SaikuQueryCache.CachedQueryResult;

public class SaikuQueryCacheTest {

    private Path tmpHome;
    private Path cacheDir;
    private SaikuQueryCache cache;

    @Before
    public void setUp() throws IOException {
        tmpHome = Files.createTempDirectory("saiku-cache-test-");
        cacheDir = tmpHome.resolve("cache");
        cache = new SaikuQueryCache(cacheDir, TimeUnit.MINUTES.toMillis(30), 268_435_456L, true);
    }

    @After
    public void tearDown() throws IOException {
        if (tmpHome != null && Files.exists(tmpHome)) {
            // Delete recursively.
            Files.walk(tmpHome)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private Supplier<CachedQueryResult> supplierProducing(byte[] bytes, long runtime, int rows, AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return new CachedQueryResult(bytes, runtime, rows, false);
        };
    }

    @Test
    public void firstGetInvokesSupplier_writesArrowAndMetaFiles() {
        AtomicInteger calls = new AtomicInteger(0);
        byte[] payload = "arrow-bytes-1".getBytes();
        String key = "key-one";

        CachedQueryResult r = cache.get(key, "v1", supplierProducing(payload, 42L, 7, calls));

        assertEquals(1, calls.get());
        assertFalse("first call should be a miss", r.cacheHit);
        assertArrayEquals(payload, r.arrowBytes);
        assertEquals(42L, r.runtimeMs);
        assertEquals(7, r.rows);
        assertTrue("arrow file missing", Files.exists(cacheDir.resolve(key + ".arrow")));
        assertTrue("meta file missing", Files.exists(cacheDir.resolve(key + ".meta.json")));
    }

    @Test
    public void secondGetIsCacheHit_doesNotInvokeSupplier() {
        AtomicInteger calls = new AtomicInteger(0);
        byte[] payload = "arrow-bytes-2".getBytes();
        String key = "key-two";

        cache.get(key, "v1", supplierProducing(payload, 5L, 3, calls));
        CachedQueryResult second = cache.get(key, "v1", supplierProducing(new byte[0], 999L, 999, calls));

        assertEquals("supplier should have been called exactly once", 1, calls.get());
        assertTrue(second.cacheHit);
        assertArrayEquals(payload, second.arrowBytes);
    }

    @Test
    public void invalidateDeletesBothFiles() {
        AtomicInteger calls = new AtomicInteger(0);
        String key = "key-three";
        cache.get(key, "v1", supplierProducing("xyz".getBytes(), 1L, 1, calls));

        assertTrue(Files.exists(cacheDir.resolve(key + ".arrow")));
        cache.invalidate(key);

        assertFalse(Files.exists(cacheDir.resolve(key + ".arrow")));
        assertFalse(Files.exists(cacheDir.resolve(key + ".meta.json")));
    }

    @Test
    public void bumpingCubeVersionInvalidates() {
        AtomicInteger calls = new AtomicInteger(0);
        String key = "key-four";
        byte[] v1Payload = "v1".getBytes();
        byte[] v2Payload = "v2".getBytes();

        cache.get(key, "cube-v1", supplierProducing(v1Payload, 1L, 1, calls));
        // Different cubeVersion means the stored entry is stale → miss + fresh store.
        CachedQueryResult afterBump = cache.get(key, "cube-v2", () -> {
            calls.incrementAndGet();
            return new CachedQueryResult(v2Payload, 9L, 2, false);
        });

        assertEquals("supplier called once per logical miss", 2, calls.get());
        assertFalse(afterBump.cacheHit);
        assertArrayEquals(v2Payload, afterBump.arrowBytes);

        // Re-read with new version → hit.
        CachedQueryResult hit = cache.get(key, "cube-v2", () -> {
            calls.incrementAndGet();
            return new CachedQueryResult(new byte[0], 0L, 0, false);
        });
        assertEquals(2, calls.get());
        assertTrue(hit.cacheHit);
    }

    @Test
    public void whenBudgetExceeded_oldestEntryEvicted() throws Exception {
        // Force a tiny budget so two 100-byte entries overflow on the second write.
        SaikuQueryCache tiny = new SaikuQueryCache(cacheDir, TimeUnit.MINUTES.toMillis(30), 150L, true);
        AtomicInteger calls = new AtomicInteger(0);

        byte[] oldPayload = new byte[100];
        byte[] newPayload = new byte[100];

        tiny.get("old", "v", supplierProducing(oldPayload, 1L, 1, calls));
        // Ensure createdAt differs.
        Thread.sleep(10);
        tiny.get("new", "v", supplierProducing(newPayload, 1L, 1, calls));

        // Eviction runs off the write path (saiku#780 perf). Drain pending
        // eviction tasks before asserting on-disk state.
        tiny.flushEvictions();

        assertFalse("oldest arrow should have been evicted", Files.exists(cacheDir.resolve("old.arrow")));
        assertFalse("oldest meta should have been evicted", Files.exists(cacheDir.resolve("old.meta.json")));
        assertTrue("newest arrow should be present", Files.exists(cacheDir.resolve("new.arrow")));
    }

    @Test
    public void put_does_not_block_on_eviction_walk() throws Exception {
        // Verifies the eviction scan happens on a background thread rather than
        // inside put()'s writeLock. We force the eviction executor to block on
        // a latch and then time how long put() takes — it must return promptly
        // even though the background eviction is still pending.
        SaikuQueryCache tiny = new SaikuQueryCache(cacheDir, TimeUnit.MINUTES.toMillis(30), 150L, true);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(0);

        // Park a long-running task on the eviction executor so any
        // synchronous "wait for eviction" code path would block.
        tiny.submitEvictionTask(() -> {
            try {
                hold.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });

        byte[] payload = new byte[100];
        long t0 = System.nanoTime();
        tiny.get("a", "v", supplierProducing(payload, 1L, 1, calls));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // Release the parked task so the executor can shut down cleanly.
        hold.countDown();

        // Even with a blocked executor, put() must return without waiting on it.
        // 250ms is generous — a synchronous wait on the latch would never return.
        assertTrue("put() must not block on eviction executor; took " + elapsedMs + "ms", elapsedMs < 250L);
    }

    @Test
    public void disabledCache_alwaysCallsSupplier_noFilesWritten() {
        SaikuQueryCache off = new SaikuQueryCache(cacheDir, TimeUnit.MINUTES.toMillis(30), 268_435_456L, false);
        AtomicInteger calls = new AtomicInteger(0);

        off.get("k", "v", supplierProducing("a".getBytes(), 1L, 1, calls));
        off.get("k", "v", supplierProducing("a".getBytes(), 1L, 1, calls));

        assertEquals(2, calls.get());
        assertFalse(Files.exists(cacheDir.resolve("k.arrow")));
    }

    @Test
    public void ttlExpiredEntryIsEvictedOnRead() throws Exception {
        // 1ms TTL → immediately stale.
        SaikuQueryCache shortTtl = new SaikuQueryCache(cacheDir, 1L, 268_435_456L, true);
        AtomicInteger calls = new AtomicInteger(0);

        shortTtl.get("k", "v", supplierProducing("a".getBytes(), 1L, 1, calls));
        Thread.sleep(10);
        CachedQueryResult r = shortTtl.get("k", "v", supplierProducing("b".getBytes(), 2L, 2, calls));

        assertEquals(2, calls.get());
        assertFalse(r.cacheHit);
    }

    @Test
    public void invalidateAll_clearsIndexAndDeletesAllCacheFiles() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k1", "v", supplierProducing("a".getBytes(), 1, 1, calls));
        cache.get("k2", "v", supplierProducing("b".getBytes(), 1, 1, calls));

        // Sanity: both keys on disk.
        assertTrue(Files.exists(cacheDir.resolve("k1.arrow")));
        assertTrue(Files.exists(cacheDir.resolve("k2.arrow")));

        cache.invalidateAll();

        assertFalse(Files.exists(cacheDir.resolve("k1.arrow")));
        assertFalse(Files.exists(cacheDir.resolve("k1.meta.json")));
        assertFalse(Files.exists(cacheDir.resolve("k2.arrow")));
        assertFalse(Files.exists(cacheDir.resolve("k2.meta.json")));
    }

    @Test
    public void invalidateAll_doesNotTouchUnrelatedFiles() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k1", "v", supplierProducing("a".getBytes(), 1, 1, calls));
        // Drop a stranger file alongside cache files.
        Path stranger = cacheDir.resolve("README.txt");
        Files.writeString(stranger, "do not delete me");

        cache.invalidateAll();

        assertTrue("non-saiku files must survive invalidateAll", Files.exists(stranger));
    }

    @Test
    public void totalBytesOnDisk_sumsMetadataBytes() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k1", "v", supplierProducing(new byte[100], 1, 1, calls));
        cache.get("k2", "v", supplierProducing(new byte[250], 1, 1, calls));

        assertEquals(350L, cache.totalBytesOnDisk());
    }

    @Test
    public void totalBytesOnDisk_zeroForEmptyCache() {
        assertEquals(0L, cache.totalBytesOnDisk());
    }

    @Test
    public void put_ignoresNullBytes() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k-null", "v", () -> {
            calls.incrementAndGet();
            return new CachedQueryResult(null, 1L, 1, false);
        });
        assertFalse(
                "supplier with null bytes must not write a cache file", Files.exists(cacheDir.resolve("k-null.arrow")));
        assertFalse(Files.exists(cacheDir.resolve("k-null.meta.json")));
    }

    @Test
    public void put_ignoresNullResult() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k-null-result", "v", () -> {
            calls.incrementAndGet();
            return null;
        });
        assertFalse(Files.exists(cacheDir.resolve("k-null-result.arrow")));
    }

    @Test
    public void reindex_warmsIndexFromExistingMetaFiles() throws Exception {
        // Populate the first cache, then construct a fresh one over the same
        // directory — its index should be warmed from the existing meta files.
        AtomicInteger calls = new AtomicInteger();
        cache.get("warmed", "v", supplierProducing("payload".getBytes(), 5, 2, calls));
        cache.shutdown();

        SaikuQueryCache reopened = new SaikuQueryCache(cacheDir, TimeUnit.MINUTES.toMillis(30), 268_435_456L, true);
        try {
            // A get with a brand-new supplier should hit on the reopened cache —
            // proving the index was warmed from disk.
            CachedQueryResult r = reopened.get("warmed", "v", supplierProducing("FRESH".getBytes(), 99, 99, calls));
            assertTrue("reopened cache should serve warmed entry as a hit", r.cacheHit);
            assertArrayEquals("payload".getBytes(), r.arrowBytes);
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    public void reindex_deletesOrphanArrowFilesWithoutSidecar() throws Exception {
        // Crash simulation: arrow file present but sidecar missing.
        Files.createDirectories(cacheDir);
        Path orphan = cacheDir.resolve("orphan.arrow");
        Files.write(orphan, "leak".getBytes());

        // New cache instance reindexes — orphan should be reaped.
        SaikuQueryCache reopened = new SaikuQueryCache(cacheDir, TimeUnit.MINUTES.toMillis(30), 268_435_456L, true);
        try {
            assertFalse("orphan arrow file without sidecar must be deleted on reindex", Files.exists(orphan));
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    public void getOnMissingArrowFile_invalidatesEntryAndRefetches() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k-tampered", "v", supplierProducing("orig".getBytes(), 1, 1, calls));

        // Out-of-band delete only the arrow file. Next get() should detect the
        // miss, invalidate the index entry, and call the supplier again.
        Files.delete(cacheDir.resolve("k-tampered.arrow"));

        CachedQueryResult r = cache.get("k-tampered", "v", supplierProducing("refetched".getBytes(), 2, 2, calls));
        assertEquals(2, calls.get());
        assertFalse(r.cacheHit);
        assertArrayEquals("refetched".getBytes(), r.arrowBytes);
    }

    @Test
    public void isEnabledReportsConstructorFlag() {
        SaikuQueryCache off = new SaikuQueryCache(cacheDir, 1L, 1L, false);
        try {
            assertFalse(off.isEnabled());
            assertTrue(cache.isEnabled());
        } finally {
            off.shutdown();
        }
    }

    @Test
    public void getCacheDirReturnsConstructorPath() {
        assertEquals(cacheDir, cache.getCacheDir());
    }

    @Test
    public void shutdown_isIdempotent() {
        // Two shutdowns in a row must not throw — sweeper / eviction executor
        // both use shutdownNow() which tolerates already-terminated state.
        cache.shutdown();
        cache.shutdown();
    }

    @Test
    public void lookupSkipsCubeVersionMismatchOnlyWhenBothNonNull() throws Exception {
        // When the request's cubeVersion is null, an entry's stored cubeVersion
        // does NOT cause a miss — the caller opted out of versioning.
        AtomicInteger calls = new AtomicInteger();
        cache.get("k-cv", "stored", supplierProducing("first".getBytes(), 1, 1, calls));

        CachedQueryResult nullVer = cache.get("k-cv", null, supplierProducing("ignored".getBytes(), 9, 9, calls));
        assertEquals(1, calls.get());
        assertTrue("null cubeVersion should hit regardless of stored version", nullVer.cacheHit);
        assertArrayEquals("first".getBytes(), nullVer.arrowBytes);
    }
}
