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
 * <p><b>saiku#1920 — the token carries an expiry.</b> The wire form is
 * {@code <expiryEpochSeconds>.<base64url(HMAC-SHA256(key, address + "\n" + expiry))>}: the expiry is
 * inside the signed material, so it cannot be extended by editing the token, and a captured link
 * stops working once it lapses. The TTL is {@value #DEFAULT_TTL_DAYS} days by default, overridable
 * per-deployment with {@code SAIKU_UNSUBSCRIBE_TOKEN_TTL_DAYS} /
 * {@code saiku.mail.unsubscribe.token.ttlDays} (0 or negative = links never expire, the pre-#1920
 * behaviour, kept only for operators who explicitly opt back in). Impact of a leaked link stays
 * bounded regardless: it can only ever ADD to suppression — it can never enable a send.
 */
public final class UnsubscribeTokens {

    private static final String HMAC_ALGO = "HmacSHA256";
    /** Domain-separation label so the HMAC key is distinct from the raw AES encryption key. */
    private static final byte[] LABEL = "saiku:unsubscribe:v1".getBytes(StandardCharsets.US_ASCII);

    /** saiku#1920 — default link lifetime, in days. */
    public static final int DEFAULT_TTL_DAYS = 180;

    /** Env override for the link lifetime; a non-positive value means "never expires". */
    public static final String ENV_TTL_DAYS = "SAIKU_UNSUBSCRIBE_TOKEN_TTL_DAYS";

    /** System-property override for the link lifetime; wins over the env var. */
    public static final String PROP_TTL_DAYS = "saiku.mail.unsubscribe.token.ttlDays";

    /** The separator between the expiry and the signature in the wire form. */
    private static final char DOT = '.';

    private final byte[] hmacKey;
    /** Link lifetime in milliseconds; {@code <= 0} means the token never expires. */
    private final long ttlMillis;
    /** Clock seam so expiry is testable without sleeping. */
    private final java.time.Clock clock;

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
        this(installKeyMaterial, configuredTtlDays(), java.time.Clock.systemUTC());
    }

    /**
     * Test ctor pinning both the TTL (in days; {@code <= 0} = never expires) and the clock, so the
     * expiry written into a token is reproducible without sleeping.
     */
    UnsubscribeTokens(byte[] installKeyMaterial, long ttlDays, java.time.Clock clock) {
        this.hmacKey = deriveHmacKey(installKeyMaterial);
        this.ttlMillis = ttlDays * 86400_000L;
        this.clock = clock;
    }

    /** TTL in days: system property > env var > {@link #DEFAULT_TTL_DAYS}. */
    private static long configuredTtlDays() {
        String v = System.getProperty(PROP_TTL_DAYS);
        if (v == null || v.isBlank()) {
            v = System.getenv(ENV_TTL_DAYS);
        }
        if (v == null || v.isBlank()) {
            return DEFAULT_TTL_DAYS;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_TTL_DAYS;
        }
    }

    /**
     * The unsubscribe token for {@code address}, or {@code null} if the address is null / blank /
     * malformed (nothing to sign). URL-safe base64 signature, no padding, prefixed with the expiry
     * the signature covers.
     */
    public String tokenFor(String address) {
        return tokenFor(address, clock.millis());
    }

    /** {@link #tokenFor(String)} with an explicit "now", so the expiry is reproducible in tests. */
    String tokenFor(String address, long nowMillis) {
        String normalised = normalise(address);
        if (normalised == null) {
            return null;
        }
        long expiry = expiryFor(nowMillis);
        byte[] mac = hmac(signedMaterial(normalised, expiry));
        return expiry + "" + DOT
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    /** Absolute expiry (epoch seconds) a token minted at {@code nowMillis} carries. */
    private long expiryFor(long nowMillis) {
        if (ttlMillis <= 0) {
            // Opted out of expiry: pin at 0 and treat 0 as "no expiry" on verify.
            return 0L;
        }
        return (nowMillis + ttlMillis) / 1000L;
    }

    /**
     * Constant-time verify that {@code token} is the valid, unexpired unsubscribe token for
     * {@code address}. Returns {@code false} for any null / blank / malformed input, a wrong /
     * tampered token, an expired token, or a token minted for a different address. Never throws.
     */
    public boolean verify(String address, String token) {
        return verify(address, token, clock.millis());
    }

    /** {@link #verify(String, String)} with an explicit "now", so expiry is testable. */
    boolean verify(String address, String token, long nowMillis) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        int dot = trimmed.indexOf(DOT);
        if (dot <= 0 || dot == trimmed.length() - 1) {
            return false;
        }
        String normalised = normalise(address);
        if (normalised == null) {
            return false;
        }
        long expiry;
        try {
            expiry = Long.parseLong(trimmed.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (expiry != 0L && nowMillis / 1000L > expiry) {
            // Expired. The endpoint's response is generic anyway, so this leaks nothing.
            return false;
        }
        // Recompute the whole token (expiry + signature) and compare constant-time, so no
        // length/charset quirk in the supplied token can leak timing and no partial
        // comparison short-circuits on the expiry prefix.
        String expected = expiry + "" + DOT
                + java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(hmac(signedMaterial(normalised, expiry)));
        try {
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII), trimmed.getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- internals ----

    /** Exactly the bytes the signature covers: the normalised address + the expiry it is bound to. */
    private static byte[] signedMaterial(String normalisedAddress, long expiry) {
        return (normalisedAddress + "\n" + expiry).getBytes(StandardCharsets.UTF_8);
    }

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
