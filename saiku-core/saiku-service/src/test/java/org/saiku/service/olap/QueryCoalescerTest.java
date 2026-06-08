/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Locks the in-flight query coalescer (saiku#946). */
public class QueryCoalescerTest {

    @Test
    public void coalescesConcurrentIdenticalToOneExecution() throws Exception {
        QueryCoalescer c = new QueryCoalescer(true, Duration.ofSeconds(5), 100);
        AtomicInteger execs = new AtomicInteger();
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futs.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return c.coalesce("same-key", () -> {
                    execs.incrementAndGet();
                    try {
                        Thread.sleep(300); // hold the in-flight window open
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return "RESULT";
                });
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown(); // fire all 12 at once
        for (Future<String> f : futs) {
            assertEquals("RESULT", f.get(5, TimeUnit.SECONDS));
        }
        assertEquals("12 concurrent identical calls must run the supplier ONCE", 1, execs.get());
        pool.shutdownNow();
    }

    @Test
    public void distinctKeysExecuteIndependently() {
        QueryCoalescer c = new QueryCoalescer(true, Duration.ofSeconds(5), 100);
        AtomicInteger execs = new AtomicInteger();
        c.coalesce("a", () -> execs.incrementAndGet());
        c.coalesce("b", () -> execs.incrementAndGet());
        assertEquals(2, execs.get());
    }

    @Test
    public void sameKeyWithinWindowReusesResult() {
        QueryCoalescer c = new QueryCoalescer(true, Duration.ofSeconds(5), 100);
        AtomicInteger execs = new AtomicInteger();
        int first = c.coalesce("k", () -> execs.incrementAndGet());
        int second = c.coalesce("k", () -> execs.incrementAndGet());
        assertEquals(1, execs.get()); // second call served from the window
        assertEquals(first, second);
    }

    @Test
    public void clearForcesRecompute() {
        QueryCoalescer c = new QueryCoalescer(true, Duration.ofSeconds(5), 100);
        AtomicInteger execs = new AtomicInteger();
        c.coalesce("k", () -> execs.incrementAndGet());
        c.coalesce("k", () -> execs.incrementAndGet()); // cached → still 1
        assertEquals(1, execs.get());
        c.clear();
        c.coalesce("k", () -> execs.incrementAndGet()); // recompute
        assertEquals(2, execs.get());
    }

    @Test
    public void disabled_isPassThrough() {
        QueryCoalescer c = new QueryCoalescer(false, Duration.ofSeconds(5), 100);
        AtomicInteger execs = new AtomicInteger();
        c.coalesce("k", () -> execs.incrementAndGet());
        c.coalesce("k", () -> execs.incrementAndGet());
        assertEquals("disabled coalescer must execute every call", 2, execs.get());
        assertTrue(!c.isEnabled());
    }

    @Test
    public void blankOrNullKey_isPassThrough() {
        QueryCoalescer c = new QueryCoalescer(true, Duration.ofSeconds(5), 100);
        AtomicInteger execs = new AtomicInteger();
        c.coalesce("", () -> execs.incrementAndGet());
        c.coalesce(null, () -> execs.incrementAndGet());
        assertEquals(2, execs.get());
    }

    @Test
    public void exceptionPropagates_andIsNotCached() {
        QueryCoalescer c = new QueryCoalescer(true, Duration.ofSeconds(5), 100);
        AtomicInteger execs = new AtomicInteger();
        try {
            c.coalesce("k", () -> {
                execs.incrementAndGet();
                throw new IllegalStateException("boom");
            });
            fail("expected the supplier exception to propagate");
        } catch (IllegalStateException expected) {
            // good
        }
        // A failed computation must not be cached — the next call retries.
        int ok = c.coalesce("k", () -> {
            execs.incrementAndGet();
            return 42;
        });
        assertEquals(42, ok);
        assertEquals(2, execs.get());
    }
}
