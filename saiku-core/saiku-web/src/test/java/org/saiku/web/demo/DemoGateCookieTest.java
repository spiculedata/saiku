/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.demo;

import static org.junit.Assert.*;

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
        assertEquals(c.sign("User@Example.com"), c.sign("  user@example.com "));
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
