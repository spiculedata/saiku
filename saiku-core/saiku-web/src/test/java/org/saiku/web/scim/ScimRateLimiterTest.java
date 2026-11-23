/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * saiku#1438 acceptance criterion: "Rate-limited to 100 req/min per token".
 *
 * <p>The window is injected so the rollover is testable without sleeping; the default constructor's
 * 100/min is asserted separately as the shipped default.
 */
public class ScimRateLimiterTest {

    @Test
    public void defaultIsHundredPerMinute() {
        ScimRateLimiter limiter = new ScimRateLimiter();
        assertEquals(100, limiter.getMaxCalls());
        assertEquals(60_000L, limiter.getWindowMs());
    }

    @Test
    public void allowsExactlyTheBudgetThenRejects() {
        ScimRateLimiter limiter = new ScimRateLimiter(3, 60_000L);
        assertTrue(limiter.tryAcquire("token-a"));
        assertTrue(limiter.tryAcquire("token-a"));
        assertTrue(limiter.tryAcquire("token-a"));
        assertFalse("the 4th call in the window is over budget", limiter.tryAcquire("token-a"));
    }

    @Test
    public void budgetIsPerToken() {
        ScimRateLimiter limiter = new ScimRateLimiter(1, 60_000L);
        assertTrue(limiter.tryAcquire("token-a"));
        assertFalse(limiter.tryAcquire("token-a"));
        assertTrue("a different token has its own budget", limiter.tryAcquire("token-b"));
    }

    @Test
    public void windowRollsOver() throws Exception {
        ScimRateLimiter limiter = new ScimRateLimiter(1, 50L);
        assertTrue(limiter.tryAcquire("token-a"));
        assertFalse(limiter.tryAcquire("token-a"));
        Thread.sleep(90L);
        assertTrue("a new window restores the budget", limiter.tryAcquire("token-a"));
    }

    @Test
    public void nullKeyFailsOpen() {
        // A wiring gap must degrade to unthrottled, never to "provisioning is broken".
        ScimRateLimiter limiter = new ScimRateLimiter(1, 60_000L);
        assertTrue(limiter.tryAcquire(null));
        assertTrue(limiter.tryAcquire(""));
    }

    @Test
    public void retryAfterMatchesTheWindow() {
        assertEquals(60L, new ScimRateLimiter().retryAfterSeconds());
    }
}
