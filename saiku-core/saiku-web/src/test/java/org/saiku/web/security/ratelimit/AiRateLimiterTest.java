/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** saiku#1151 — unit tests for the cost-DoS call-rate limiter. */
public class AiRateLimiterTest {

    @Test
    public void allowsUpToMaxThenBlocksWithinWindow() {
        AiRateLimiter limiter = new AiRateLimiter(3, 60_000L);
        assertTrue("call 1 allowed", limiter.tryAcquire("user:1.2.3.4"));
        assertTrue("call 2 allowed", limiter.tryAcquire("user:1.2.3.4"));
        assertTrue("call 3 allowed", limiter.tryAcquire("user:1.2.3.4"));
        assertFalse("call 4 over budget", limiter.tryAcquire("user:1.2.3.4"));
        assertFalse("still blocked", limiter.tryAcquire("user:1.2.3.4"));
    }

    @Test
    public void budgetIsPerKey() {
        AiRateLimiter limiter = new AiRateLimiter(1, 60_000L);
        assertTrue(limiter.tryAcquire("alice:1.1.1.1"));
        assertFalse("alice exhausted", limiter.tryAcquire("alice:1.1.1.1"));
        // A different key (different principal or IP) has its own budget.
        assertTrue("bob unaffected", limiter.tryAcquire("bob:1.1.1.1"));
        assertTrue("same user, different IP unaffected", limiter.tryAcquire("alice:2.2.2.2"));
    }

    @Test
    public void windowRolloverResetsBudget() {
        // A negative window is always "already elapsed" (now - start > -1 holds
        // for any clock), so each call deterministically resets the bucket to a
        // fresh window — exercising the rollover branch without sleeping.
        AiRateLimiter limiter = new AiRateLimiter(1, -1L);
        assertTrue(limiter.tryAcquire("user:1.2.3.4"));
        assertTrue("window already elapsed → counter reset", limiter.tryAcquire("user:1.2.3.4"));
        assertTrue(limiter.tryAcquire("user:1.2.3.4"));
    }

    @Test
    public void nullKeyFailsOpen() {
        AiRateLimiter limiter = new AiRateLimiter(0, 60_000L);
        assertTrue("null identity is allowed through, not bricked", limiter.tryAcquire(null));
    }

    // ---- saiku#1913: the per-request-instance bug this file's fix is about ----

    /**
     * The regression itself: a limiter rebuilt per request (as every {@code scope="request"}
     * resource used to do) carries an empty bucket map, so the cap NEVER trips. Two
     * {@code new AiRateLimiter(...)} instances must be independent.
     */
    @Test
    public void privateStoresAreNotShared() {
        AiRateLimiter perRequest1 = new AiRateLimiter(1, 60_000L);
        assertTrue(perRequest1.tryAcquire("user:1.2.3.4"));
        AiRateLimiter perRequest2 = new AiRateLimiter(1, 60_000L);
        assertTrue("a fresh instance has a fresh budget — the #1913 bug", perRequest2.tryAcquire("user:1.2.3.4"));
    }

    /** {@code shared(name)} is what production wiring uses: separate objects, ONE budget. */
    @Test
    public void sharedNameKeepsOneBudgetAcrossInstances() {
        String name = "test.shared.across.instances";
        AiRateLimiter first = AiRateLimiter.shared(name, 2, 60_000L);
        AiRateLimiter second = AiRateLimiter.shared(name, 2, 60_000L);
        assertTrue(first.tryAcquire("k"));
        assertTrue(second.tryAcquire("k"));
        assertFalse("third call over the shared budget", second.tryAcquire("k"));
        assertFalse(first.tryAcquire("k"));
    }

    @Test
    public void differentNamesDoNotShareBudget() {
        AiRateLimiter a = AiRateLimiter.shared("test.shared.name.a", 1, 60_000L);
        AiRateLimiter b = AiRateLimiter.shared("test.shared.name.b", 1, 60_000L);
        assertTrue(a.tryAcquire("k"));
        assertFalse(a.tryAcquire("k"));
        assertTrue("an unrelated limiter has its own budget", b.tryAcquire("k"));
    }

    @Test
    public void sharedStoreReportsItsName() {
        AiRateLimiter shared = AiRateLimiter.shared("test.shared.name.reporting", 5, 1000L);
        assertEquals("test.shared.name.reporting", shared.getStoreName());
        assertNull("a plain constructor is the private form", new AiRateLimiter().getStoreName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void sharedRejectsBlankName() {
        AiRateLimiter.shared("  ", 5, 1000L);
    }

    /** The shared store is attacker-keyable (client IP), so it must not grow without bound. */
    @Test
    public void sharedStoreStaysBounded() {
        AiRateLimiter limiter = AiRateLimiter.shared("test.shared.bounded", Integer.MAX_VALUE, 60_000L);
        for (int i = 0; i < AiRateLimiter.MAX_TRACKED_KEYS + 10; i++) {
            assertTrue(limiter.tryAcquire("key:" + i));
        }
        assertTrue("store is dropped rather than growing past the cap", limiter.tryAcquire("key:after"));
    }
}
