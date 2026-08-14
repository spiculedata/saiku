/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.saiku.datasources.connection.encrypt.CryptoUtil;

/**
 * Signs and verifies one-click unsubscribe tokens (saiku#1811, PR2).
 *
 * <p>A token is {@code URL-safe-base64( HMAC-SHA256(hmacKey, normalisedAddress) )}. The
 * {@code hmacKey} is derived from the SAME per-install key that encrypts stored datasource passwords
 * (via {@link CryptoUtil#installKeyMaterial()}), domain-separated with a fixed label so the HMAC key
 * is never the raw encryption key. <b>No new key mechanism is invented.</b>
 *
 * <p><b>What the token proves.</b> A valid token authenticates that this recipient's OWN unsubscribe
 * link is being used: only someone holding the per-install key could have produced the HMAC for a
 * given address. An attacker cannot forge an unsubscribe for an arbitrary third-party address without
 * the key. The unsubscribe endpoint therefore NEVER accepts a raw attacker-supplied address without a
 * matching valid token — that is the anti-forgery boundary.
 *
 * <p>Verification is constant-time ({@link MessageDigest#isEqual}) to avoid a timing oracle, and the
 * address is normalised (lowercase, CRLF-stripped, RFC-validated) identically on sign and verify so a
 * cosmetic variant of the same address still verifies.
 *
 * <p>Stateless: the token carries no expiry of its own (RFC 8058 one-click links are long-lived); a
 * token stays valid for the life of the per-install key, which is exactly the desired property for an
 * unsubscribe link that only ever ADDS to suppression (fail-safe — it can never enable a send).
 */
public final class UnsubscribeTokens {

    private static final String HMAC_ALGO = "HmacSHA256";
    /** Domain-separation label so the HMAC key is distinct from the raw AES encryption key. */
    private static final byte[] LABEL = "saiku:unsubscribe:v1".getBytes(StandardCharsets.US_ASCII);

    private final byte[] hmacKey;

    /** Production ctor: derives the HMAC key from the per-install key. */
    public UnsubscribeTokens() {
        this(CryptoUtil.installKeyMaterial());
    }

    /**
     * Test/explicit ctor: derives the HMAC key from the supplied install key material. The material is
     * domain-separated (HMAC'd under {@link #LABEL}) before use, so the stored HMAC key is never the
     * caller's raw bytes.
     */
    public UnsubscribeTokens(byte[] installKeyMaterial) {
        this.hmacKey = deriveHmacKey(installKeyMaterial);
    }

    /**
     * The unsubscribe token for {@code address}, or {@code null} if the address is null / blank /
     * malformed (nothing to sign). URL-safe base64, no padding.
     */
    public String tokenFor(String address) {
        String normalised = normalise(address);
        if (normalised == null) {
            return null;
        }
        byte[] mac = hmac(normalised.getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    /**
     * Constant-time verify that {@code token} is the valid unsubscribe token for {@code address}.
     * Returns {@code false} for any null / blank / malformed input, a wrong / tampered token, or a
     * token minted for a different address. Never throws.
     */
    public boolean verify(String address, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String expected = tokenFor(address);
        if (expected == null) {
            return false;
        }
        // Compare the RAW MAC bytes constant-time rather than the base64 strings, so length/charset
        // quirks in the supplied token can't leak timing. A malformed base64 token decodes to bytes
        // that simply won't match.
        byte[] expectedBytes;
        byte[] suppliedBytes;
        try {
            expectedBytes = java.util.Base64.getUrlDecoder().decode(expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            suppliedBytes = java.util.Base64.getUrlDecoder().decode(token.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expectedBytes, suppliedBytes);
    }

    // ---- internals ----

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static byte[] deriveHmacKey(byte[] installKeyMaterial) {
        if (installKeyMaterial == null || installKeyMaterial.length == 0) {
            throw new IllegalStateException("install key material unavailable for unsubscribe HMAC");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(installKeyMaterial, HMAC_ALGO));
            return mac.doFinal(LABEL);
        } catch (Exception e) {
            throw new IllegalStateException("Unsubscribe HMAC key derivation failed", e);
        }
    }

    /** Lowercase + CRLF-strip + RFC-validate; null when blank/invalid. Matches the store's rule. */
    private static String normalise(String address) {
        if (address == null) {
            return null;
        }
        String s = address.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.toLowerCase();
        try {
            jakarta.mail.internet.InternetAddress ia = new jakarta.mail.internet.InternetAddress(s, true);
            ia.validate();
            String bare = ia.getAddress();
            return bare == null ? null : bare.toLowerCase();
        } catch (jakarta.mail.internet.AddressException e) {
            return null;
        }
    }
}
