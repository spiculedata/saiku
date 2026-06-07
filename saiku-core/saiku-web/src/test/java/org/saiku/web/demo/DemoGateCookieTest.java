/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.demo;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public class DemoGateCookieTest {

    private static final String SECRET = "test-secret-key-aaaaaaaaaaaaaaaaaaaaaa";

    @Test
    public void signThenVerify_roundTrips() {
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        assertTrue(c.verify(c.sign("user@example.com")));
    }

    @Test
    public void verify_rejectsTamperedSignature() {
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        String v = c.sign("user@example.com");
        char last = v.charAt(v.length() - 1);
        String tampered = v.substring(0, v.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertFalse(c.verify(tampered));
    }

    @Test
    public void verify_rejectsForgedPayloadWithOldSignature() {
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        String legit = c.sign("user@example.com");
        String evil = c.sign("evil@example.com");
        // Splice evil's payload onto legit's signature — must not validate.
        String forged = evil.substring(0, evil.indexOf('.')) + legit.substring(legit.indexOf('.'));
        assertFalse(c.verify(forged));
    }

    @Test
    public void verify_rejectsWrongSecret() {
        String v = new DemoGateCookie(SECRET, 100).sign("user@example.com");
        assertFalse(new DemoGateCookie("a-totally-different-secret-bbbbbbbbbb", 100).verify(v));
    }

    @Test
    public void verify_rejectsMalformed() {
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        assertFalse(c.verify(null));
        assertFalse(c.verify(""));
        assertFalse(c.verify("noseparator"));
        assertFalse(c.verify(".sigonly"));
        assertFalse(c.verify("payloadonly."));
        assertFalse(c.verify("!!!.@@@"));
    }

    @Test
    public void email_normalizedForCaseAndWhitespace() {
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        // Fixed expiry so the comparison is deterministic (sign() embeds now+ttl).
        assertEquals(c.sign("User@Example.com", 123456789L), c.sign("  user@example.com ", 123456789L));
    }

    @Test
    public void verify_rejectsExpiredMarker() {
        // #1156: a marker whose signed expiry has lapsed must not validate.
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        assertFalse(c.verify(c.sign("user@example.com", 1000L))); // epoch 1000s = long past
    }

    @Test
    public void verify_acceptsNotYetExpiredMarker() {
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        long future = System.currentTimeMillis() / 1000L + 3600;
        assertTrue(c.verify(c.sign("user@example.com", future)));
    }

    @Test
    public void verify_rejectsClientExtendedExpiry() {
        // #1156: re-encoding the payload with a later expiry but reusing the
        // original signature must fail — the expiry is part of the signed data.
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        String v = c.sign("user@example.com", 1000L); // expired
        String sig = v.substring(v.indexOf('.') + 1);
        long farFuture = System.currentTimeMillis() / 1000L + 31_536_000L;
        String forgedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("user@example.com|" + farFuture).getBytes(StandardCharsets.UTF_8));
        assertFalse(c.verify(forgedPayload + "." + sig));
    }

    @Test
    public void emailOf_recoversNormalizedEmail() {
        DemoGateCookie c = new DemoGateCookie(SECRET, 100);
        assertEquals("user@example.com", c.emailOf(c.sign("User@Example.com")));
    }

    @Test
    public void setCookieHeader_carriesHardenedAttributes() {
        DemoGateCookie c = new DemoGateCookie(SECRET, 2592000);
        String h = c.setCookieHeader("user@example.com", true);
        assertTrue(h.startsWith(DemoGateCookie.COOKIE_NAME + "="));
        assertTrue(h.contains("; Max-Age=2592000"));
        assertTrue(h.contains("; Path=/"));
        assertTrue(h.contains("; HttpOnly"));
        assertTrue(h.contains("; SameSite=Strict"));
        assertTrue(h.contains("; Secure"));
    }

    @Test
    public void setCookieHeader_omitsSecureWhenInsecure() {
        String h = new DemoGateCookie(SECRET, 100).setCookieHeader("user@example.com", false);
        assertFalse(h.contains("Secure"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankSecretRejected() {
        new DemoGateCookie("   ", 100);
    }
}
