/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * saiku#1811 PR2 — HMAC unsubscribe tokens. Locks the anti-forgery boundary: a token verifies ONLY
 * for the exact address it was minted for, under the exact per-install key; a forged, tampered,
 * wrong-address, or wrong-key token is rejected. Uses the explicit key ctor so no install-key file is
 * touched.
 */
class UnsubscribeTokensTest {

    private static final byte[] KEY = "test-install-key-material-32-byteXX".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY = "a-totally-different-install-key-yy".getBytes(StandardCharsets.UTF_8);

    private UnsubscribeTokens tokens() {
        return new UnsubscribeTokens(KEY);
    }

    /** Pinned clock + TTL, so the expiry written into a token is reproducible. */
    private static UnsubscribeTokens tokensAt(long ttlDays, long fixedNowMillis) {
        return new UnsubscribeTokens(
                KEY,
                ttlDays,
                java.time.Clock.fixed(java.time.Instant.ofEpochMilli(fixedNowMillis), java.time.ZoneOffset.UTC));
    }

    /** A fixed instant so TTL arithmetic is readable in the assertions above. */
    private static final long NOW_MS = 1_750_000_000_000L;

    @Test
    void verify_acceptsTheCorrectToken() {
        UnsubscribeTokens t = tokens();
        String token = t.tokenFor("alice@example.com");
        assertNotNull(token);
        assertTrue(t.verify("alice@example.com", token));
    }

    @Test
    void verify_isCaseAndWhitespaceInsensitive_onAddress() {
        UnsubscribeTokens t = tokens();
        String token = t.tokenFor("alice@example.com");
        // A cosmetic variant of the same address still verifies (both sides normalise identically).
        assertTrue(t.verify("ALICE@Example.COM", token));
        assertTrue(t.verify("  alice@example.com ", token));
    }

    @Test
    void verify_rejectsTokenForADifferentAddress() {
        UnsubscribeTokens t = tokens();
        String token = t.tokenFor("alice@example.com");
        // The attacker holds alice's valid token but tries to unsubscribe bob — must fail.
        assertFalse(t.verify("bob@example.com", token));
    }

    @Test
    void verify_rejectsTamperedToken() {
        UnsubscribeTokens t = tokens();
        String token = t.tokenFor("alice@example.com");
        String tampered = flipFirstChar(token);
        assertNotEquals(token, tampered);
        assertFalse(t.verify("alice@example.com", tampered));
    }

    @Test
    void verify_rejectsTokenMintedUnderADifferentKey() {
        String foreign = new UnsubscribeTokens(OTHER_KEY).tokenFor("alice@example.com");
        // A token signed with someone else's install key must not verify here.
        assertFalse(tokens().verify("alice@example.com", foreign));
    }

    @Test
    void verify_rejectsNullBlankAndGarbageTokens() {
        UnsubscribeTokens t = tokens();
        assertFalse(t.verify("alice@example.com", null));
        assertFalse(t.verify("alice@example.com", ""));
        assertFalse(t.verify("alice@example.com", "   "));
        assertFalse(t.verify("alice@example.com", "!!!not-base64!!!"));
        assertFalse(t.verify("alice@example.com", "AAAA"));
    }

    @Test
    void verify_rejectsNullOrMalformedAddress() {
        UnsubscribeTokens t = tokens();
        String token = t.tokenFor("alice@example.com");
        assertFalse(t.verify(null, token));
        assertFalse(t.verify("", token));
        assertFalse(t.verify("not-an-email", token));
    }

    @Test
    void tokenFor_isNull_forMalformedAddress() {
        UnsubscribeTokens t = tokens();
        assertNull(t.tokenFor(null));
        assertNull(t.tokenFor("   "));
        assertNull(t.tokenFor("not-an-email"));
    }

    @Test
    void token_isDeterministic_forTheSameAddressKeyAndClock() {
        // Pinned clock — the expiry is part of the signed material, so two tokens minted
        // in the same clock-instant are identical and different addresses diverge.
        UnsubscribeTokens t = tokensAt(30, 1_750_000_000_000L);
        assertEquals(t.tokenFor("alice@example.com"), t.tokenFor("alice@example.com"));
        assertNotEquals(t.tokenFor("alice@example.com"), t.tokenFor("bob@example.com"));
    }

    /* ---------------- saiku#1920: expiry bound into the signed material ---------------- */

    @Test
    void token_carriesTheExpiry_itExpiresWith() {
        // A token minted "now" is valid now and stops being valid after its TTL — the
        // expiry lives inside the signed material, so it can't be edited to extend.
        UnsubscribeTokens t = tokensAt(30, NOW_MS);
        String token = t.tokenFor("alice@example.com", NOW_MS);
        assertTrue(t.verify("alice@example.com", token, NOW_MS));
        // 30 days later (plus the second the clock has advanced inside that window).
        assertTrue(t.verify("alice@example.com", token, NOW_MS + 29 * 86_400_000L));
        assertFalse(t.verify("alice@example.com", token, NOW_MS + 31 * 86_400_000L));
    }

    @Test
    void extendingTheExpiryInTheTokenInvalidatesIt() {
        // The attacker re-mints the same token with a far-future expiry. The signature
        // covers the expiry, so the HMAC no longer matches and verification fails.
        UnsubscribeTokens t = tokensAt(30, NOW_MS);
        String token = t.tokenFor("alice@example.com", NOW_MS);
        int dot = token.indexOf('.');
        String tampered = (Long.parseLong(token.substring(0, dot)) + 10_000L) + token.substring(dot);
        assertFalse(t.verify("alice@example.com", tampered, NOW_MS));
    }

    @Test
    void shorteningTheExpiryInTheTokenInvalidatesIt() {
        // Same in the other direction — the expiry is not a hint, it is signed material.
        UnsubscribeTokens t = tokensAt(30, NOW_MS);
        String token = t.tokenFor("alice@example.com", NOW_MS);
        int dot = token.indexOf('.');
        String tampered = (Long.parseLong(token.substring(0, dot)) - 10_000L) + token.substring(dot);
        assertFalse(t.verify("alice@example.com", tampered, NOW_MS));
    }

    @Test
    void tokensWithDifferentMintTimesProduceDifferentStrings() {
        // Two tokens for the same address minted 1 s apart have different expiries and therefore
        // different HMACs — they are distinct strings.  Both remain valid until their respective
        // expiry (continuous TTL, not slot-bound), so verify() still accepts each within its window.
        UnsubscribeTokens t = tokensAt(30, NOW_MS);
        String early = t.tokenFor("alice@example.com", NOW_MS);
        String later = t.tokenFor("alice@example.com", NOW_MS + 1_000L);
        assertNotEquals(early, later);
        // Both tokens are unexpired and have correct HMACs — each verifies at the other's mint time.
        assertTrue(t.verify("alice@example.com", early, NOW_MS + 1_000L));
        assertTrue(t.verify("alice@example.com", later, NOW_MS));
    }

    @Test
    void aNonPositiveTtlOptsOutOfExpiryEntirely() {
        // Operators who explicitly set a non-positive TTL keep the pre-#1920 behaviour.
        UnsubscribeTokens t = tokensAt(0, NOW_MS);
        String token = t.tokenFor("alice@example.com", NOW_MS);
        assertTrue(token.startsWith("0."), "no-expiry tokens pin the expiry slot at 0: " + token);
        assertTrue(t.verify("alice@example.com", token, NOW_MS + 3650L * 86_400_000L));
    }

    @Test
    void tokenFor_defaultCtorHonoursTheTtlProperty() {
        String prior = System.getProperty(UnsubscribeTokens.PROP_TTL_DAYS);
        System.setProperty(UnsubscribeTokens.PROP_TTL_DAYS, "7");
        try {
            UnsubscribeTokens t = new UnsubscribeTokens(KEY);
            long nowMs = System.currentTimeMillis();
            String token = t.tokenFor("alice@example.com", nowMs);
            assertTrue(t.verify("alice@example.com", token, nowMs));
            assertFalse(t.verify("alice@example.com", token, nowMs + 8 * 86_400_000L));
        } finally {
            if (prior == null) {
                System.clearProperty(UnsubscribeTokens.PROP_TTL_DAYS);
            } else {
                System.setProperty(UnsubscribeTokens.PROP_TTL_DAYS, prior);
            }
        }
    }

    @Test
    void defaultTtlIsOneEightyDays() {
        assertEquals(180, UnsubscribeTokens.DEFAULT_TTL_DAYS);
    }

    @Test
    void token_isUrlSafe_noPadding() {
        String token = tokens().tokenFor("alice+tag@example.com");
        assertNotNull(token);
        // URL-safe base64 uses - and _ and drops = padding; assert none of +, /, = leaked.
        assertFalse(token.contains("+"));
        assertFalse(token.contains("/"));
        assertFalse(token.contains("="));
    }

    private static String flipFirstChar(String s) {
        char c = s.charAt(0);
        char replacement = (c == 'A') ? 'B' : 'A';
        return replacement + s.substring(1);
    }
}
