/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** saiku#2339: the lockout is keyed on (client IP, username), not the IP alone. */
public class LoginRateLimiterTest {

    private static final long WINDOW_MS = 60_000L;

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    private static LoginRateLimiter limiter(int hops) {
        // 5 per (ip,user), 20 per user across IPs, 50 per IP across users
        return new LoginRateLimiter(5, WINDOW_MS, hops, 20, 50);
    }

    @Test
    public void failuresForOneUsername_doNotBlockAnotherUsernameFromSameIp() {
        LoginRateLimiter l = limiter(0);
        for (int i = 0; i < 5; i++) l.recordFailure(from("1.1.1.1"), "nobody");

        assertTrue(l.isBlocked(from("1.1.1.1"), "nobody"));
        assertFalse(l.isBlocked(from("1.1.1.1"), "alice"));
    }

    @Test
    public void usernameIsCaseInsensitive() {
        LoginRateLimiter l = limiter(0);
        for (int i = 0; i < 5; i++) l.recordFailure(from("1.1.1.1"), "Alice");

        assertTrue(l.isBlocked(from("1.1.1.1"), "alice"));
    }

    @Test
    public void failuresForOneUsernameAcrossIps_blockThatUsername() {
        LoginRateLimiter l = limiter(0);
        for (int i = 0; i < 20; i++) l.recordFailure(from("10.0.0." + i), "alice");

        assertTrue(l.isBlocked(from("9.9.9.9"), "alice"));
        assertFalse(l.isBlocked(from("9.9.9.9"), "bob"));
    }

    @Test
    public void manyUsernamesFromOneIp_hitTheIpHighWaterMark() {
        LoginRateLimiter l = limiter(0);
        for (int i = 0; i < 50; i++) l.recordFailure(from("2.2.2.2"), "user" + i);

        assertTrue(l.isBlocked(from("2.2.2.2"), "someone-new"));
        assertFalse(l.isBlocked(from("3.3.3.3"), "someone-new"));
    }

    @Test
    public void blankUsername_onlyCountsAgainstTheIpBucket() {
        LoginRateLimiter l = limiter(0);
        for (int i = 0; i < 5; i++) l.recordFailure(from("4.4.4.4"), null);

        assertFalse(l.isBlocked(from("4.4.4.4"), "alice"));
        assertFalse(l.isBlocked(from("4.4.4.4"), ""));
    }

    @Test
    public void successClearsTheUserAndIpUserBuckets() {
        LoginRateLimiter l = limiter(0);
        for (int i = 0; i < 4; i++) l.recordFailure(from("1.1.1.1"), "alice");
        l.recordSuccess(from("1.1.1.1"), "alice");
        l.recordFailure(from("1.1.1.1"), "alice");

        assertFalse(l.isBlocked(from("1.1.1.1"), "alice"));
    }

    @Test
    public void windowExpiry_unblocks() throws Exception {
        LoginRateLimiter l = new LoginRateLimiter(1, 20L, 0, 20, 50);
        l.recordFailure(from("1.1.1.1"), "alice");
        assertTrue(l.isBlocked(from("1.1.1.1"), "alice"));

        Thread.sleep(60L);

        assertFalse(l.isBlocked(from("1.1.1.1"), "alice"));
    }

    // --- saiku#2426: buckets are bounded in key length, count, and lifetime

    @Test
    public void oversizedUsernames_shareOneClampedBucket() {
        LoginRateLimiter l = new LoginRateLimiter(1, WINDOW_MS, 0, 20, 50);
        String huge1 = "x".repeat(300) + "-a";
        String huge2 = "x".repeat(300) + "-b";
        l.recordFailure(from("1.1.1.1"), huge1);

        // both clamp to the same 256-char key, so the second is already blocked
        assertTrue(l.isBlocked(from("1.1.1.1"), huge2));
        assertFalse(l.isBlocked(from("1.1.1.1"), "alice"));
    }

    @Test
    public void manyDistinctUsernames_doNotGrowTheMapsPastTheBound() {
        // high IP ceiling so the per-IP warn log does not fire on every iteration
        LoginRateLimiter l = new LoginRateLimiter(5, WINDOW_MS, 0, 20, Integer.MAX_VALUE);
        for (int i = 0; i < LoginRateLimiter.MAX_BUCKETS + 2_000; i++) {
            l.recordFailure(from("3.3.3.3"), "user-" + i);
        }

        long[] counts = l.bucketCountsForTesting();
        assertTrue(counts[0] <= LoginRateLimiter.MAX_BUCKETS);
        assertTrue(counts[1] <= LoginRateLimiter.MAX_BUCKETS);
        assertEquals(1, counts[2]);
    }

    @Test
    public void expiredBuckets_leaveTheMapWithoutBeingLookedUpAgain() throws Exception {
        LoginRateLimiter l = new LoginRateLimiter(1, 20L, 0, 20, 50);
        l.recordFailure(from("1.1.1.1"), "alice");

        Thread.sleep(60L);

        // unrelated activity triggers eviction of alice's never-re-looked-up bucket
        l.recordFailure(from("2.2.2.2"), "bob");
        long[] counts = l.bucketCountsForTesting();
        assertEquals(1, counts[0]);
        assertEquals(1, counts[1]);
        assertEquals(1, counts[2]);
        assertFalse(l.isBlocked(from("1.1.1.1"), "alice"));
    }

    // --- client IP resolution

    @Test
    public void clientIp_defaultIgnoresForwardedFor() {
        MockHttpServletRequest r = from("10.0.0.9");
        r.addHeader("X-Forwarded-For", "6.6.6.6");

        assertEquals("10.0.0.9", limiter(0).clientIp(r));
    }

    @Test
    public void clientIp_withTrustedHops_usesTheEntryJustBeforeTheTrustedProxies() {
        // two trusted hops: the peer (10.0.0.9) and 10.0.0.5, which appended 1.2.3.4
        MockHttpServletRequest r = from("10.0.0.9");
        r.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.5");

        assertEquals("1.2.3.4", limiter(2).clientIp(r));
    }

    @Test
    public void clientIp_withTrustedHops_neverUsesAttackerSuppliedLeftmostEntries() {
        MockHttpServletRequest r = from("10.0.0.9");
        r.addHeader("X-Forwarded-For", "9.9.9.9, 1.2.3.4, 10.0.0.5");

        assertEquals("1.2.3.4", limiter(2).clientIp(r));
    }

    @Test
    public void clientIp_singleProxyAppendingTheClient_usesTheLastEntry() {
        MockHttpServletRequest r = from("10.0.0.9");
        r.addHeader("X-Forwarded-For", "9.9.9.9, 1.2.3.4");

        assertEquals("1.2.3.4", limiter(1).clientIp(r));
    }

    @Test
    public void clientIp_tooFewForwardedEntries_fallsBackToRemoteAddr() {
        MockHttpServletRequest r = from("10.0.0.9");
        r.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("10.0.0.9", limiter(2).clientIp(r));
    }

    @Test
    public void rotatingTheSpoofedHeader_doesNotYieldAFreshBucket() {
        LoginRateLimiter l = limiter(1);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest r = from("10.0.0.9");
            r.addHeader("X-Forwarded-For", "10.9.9." + i + ", 1.2.3.4");
            l.recordFailure(r, "alice");
        }
        MockHttpServletRequest again = from("10.0.0.9");
        again.addHeader("X-Forwarded-For", "10.7.7.7, 1.2.3.4");

        assertTrue(l.isBlocked(again, "alice"));
    }
}
