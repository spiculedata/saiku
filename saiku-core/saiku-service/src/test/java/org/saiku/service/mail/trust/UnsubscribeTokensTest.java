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
    void token_isDeterministic_forTheSameAddressAndKey() {
        UnsubscribeTokens t = tokens();
        assertEquals(t.tokenFor("alice@example.com"), t.tokenFor("alice@example.com"));
        // Different addresses => different tokens.
        assertNotEquals(t.tokenFor("alice@example.com"), t.tokenFor("bob@example.com"));
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
