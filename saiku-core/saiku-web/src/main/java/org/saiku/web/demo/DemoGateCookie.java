/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.demo;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless, signed marker cookie proving a visitor cleared the demo email gate.
 *
 * <p>The cookie does NOT log anyone in — it only unlocks the login form. The
 * value is {@code base64url(email "|" expiryEpochSeconds) "." base64url(HMAC-SHA256(that))};
 * the HMAC key never leaves the server, so the client can't forge or read a
 * valid marker. The expiry is part of the SIGNED payload (not just the cookie's
 * {@code Max-Age}), so a captured/replayed marker stops working after it lapses
 * and the expiry can't be tampered (#1156). The email is recoverable from the
 * cookie by the server (for the verified-email log) but is opaque/tamper-evident
 * to the client.
 *
 * <p>Pure crypto/string logic — no servlet types — so it's unit-tested directly
 * (saiku-web is JUnit 4 with no mocking framework).
 */
public final class DemoGateCookie {

    /** Cookie name set on verify and checked by {@code DemoGateFilter}. */
    public static final String COOKIE_NAME = "saiku_demo_verified";

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;

    public DemoGateCookie(String secret, long ttlSeconds) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("demo gate secret must not be blank");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** Build the signed cookie value for a verified email, expiring at
     *  {@code now + ttlSeconds}. */
    public String sign(String email) {
        return sign(email, nowEpochSeconds() + ttlSeconds);
    }

    /** Sign with an explicit absolute expiry (epoch seconds). Package-private so
     *  unit tests can produce already-expired / not-yet-expired markers
     *  deterministically without mocking the clock. */
    String sign(String email, long expiresAtEpochSeconds) {
        String content = signedContent(normalize(email), expiresAtEpochSeconds);
        String payload = B64.encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String sig = B64.encodeToString(hmac(content));
        return payload + "." + sig;
    }

    /**
     * @return true iff {@code cookieValue} is well-formed, its signature matches
     *     this secret (constant-time comparison), AND it has not expired. The
     *     expiry is part of the signed payload, so it can't be extended by the
     *     client (#1156); a legacy value with no expiry field is rejected.
     */
    public boolean verify(String cookieValue) {
        if (cookieValue == null) return false;
        int dot = cookieValue.indexOf('.');
        if (dot <= 0 || dot == cookieValue.length() - 1) return false;
        String payload = cookieValue.substring(0, dot);
        String sig = cookieValue.substring(dot + 1);
        String content;
        byte[] presented;
        try {
            content = new String(B64D.decode(payload), StandardCharsets.UTF_8);
            presented = B64D.decode(sig);
        } catch (IllegalArgumentException badBase64) {
            return false;
        }
        // Signature must cover the full payload (email + expiry) — tamper-evident.
        if (!MessageDigest.isEqual(hmac(content), presented)) return false;
        // ...and the marker must not be expired. Expiry is the last
        // '|'-separated field; a value without one is an old/forged format.
        int bar = content.lastIndexOf('|');
        if (bar < 0) return false;
        long expiresAt;
        try {
            expiresAt = Long.parseLong(content.substring(bar + 1));
        } catch (NumberFormatException badExpiry) {
            return false;
        }
        return expiresAt > nowEpochSeconds();
    }

    /**
     * The email embedded in a (trusted) cookie value, or {@code null} if the
     * value is malformed. Call only after {@link #verify} returns true.
     */
    public String emailOf(String cookieValue) {
        if (cookieValue == null) return null;
        int dot = cookieValue.indexOf('.');
        if (dot <= 0) return null;
        try {
            String content = new String(B64D.decode(cookieValue.substring(0, dot)), StandardCharsets.UTF_8);
            int bar = content.lastIndexOf('|');
            return bar < 0 ? content : content.substring(0, bar);
        } catch (IllegalArgumentException badBase64) {
            return null;
        }
    }

    /**
     * Full {@code Set-Cookie} header value: {@code HttpOnly}, {@code SameSite=Strict},
     * {@code Path=/}, {@code Max-Age=ttl}, and {@code Secure} when {@code secure}
     * is true (set behind TLS — mirrors the launcher's session-cookie toggle).
     */
    public String setCookieHeader(String email, boolean secure) {
        StringBuilder sb = new StringBuilder()
                .append(COOKIE_NAME)
                .append('=')
                .append(sign(email))
                .append("; Max-Age=")
                .append(ttlSeconds)
                .append("; Path=/; HttpOnly; SameSite=Strict");
        if (secure) sb.append("; Secure");
        return sb.toString();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** The HMAC-signed content: {@code "email|expiryEpochSeconds"}. Both fields
     *  are covered by the signature, so neither the email nor the expiry can be
     *  tampered, and {@link #verify} additionally rejects a lapsed expiry. */
    private static String signedContent(String normalizedEmail, long expiresAtEpochSeconds) {
        return normalizedEmail + "|" + expiresAtEpochSeconds;
    }

    private static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}
