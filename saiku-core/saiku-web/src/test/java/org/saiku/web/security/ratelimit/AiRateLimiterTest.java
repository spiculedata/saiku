/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import static org.junit.Assert.assertFalse;
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
}
