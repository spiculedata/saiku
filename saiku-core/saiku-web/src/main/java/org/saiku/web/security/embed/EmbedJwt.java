/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * saiku#1104 Phase 1 — a deliberately small, auditable HMAC-SHA256 (HS256)
 * verifier for compact JWTs used as embed RLS tokens. The embedder mints a JWT
 * (signed with the deployment's shared secret) carrying the tenant scope +
 * forced filters; Saiku verifies it here before honouring those claims.
 *
 * <p>Hand-rolled on purpose: HS256-verify is a tiny, well-understood operation,
 * so this avoids pulling a JWT library (and its transitive CVE surface) for
 * Phase 1. Phase 2 (asymmetric / JWKS) is when a vetted library earns its keep.
 * Every classic JWT footgun is closed explicitly:
 * <ul>
 *   <li><b>{@code alg} confusion / {@code alg:none}</b> — the header {@code alg}
 *       MUST be exactly {@code "HS256"}; anything else (incl. {@code none},
 *       {@code RS256}, {@code HS384/512}) is rejected before any signature work.</li>
 *   <li><b>Forgery</b> — the signature is recomputed over the exact
 *       {@code header.payload} bytes and compared in <b>constant time</b>
 *       ({@link MessageDigest#isEqual}).</li>
 *   <li><b>Eternal tokens</b> — {@code exp} is REQUIRED (a token with no expiry
 *       is rejected, fail-closed) and checked with a small clock-skew leeway.</li>
 *   <li><b>Audience confusion</b> — when an expected audience is configured the
 *       token's {@code aud} must match it.</li>
 * </ul>
 *
 * <p>Every rejection throws {@link EmbedJwtException}; the caller collapses all
 * of them into one opaque {@code EMBED_INVALID} response so a probe learns
 * nothing about why a token failed.
 */
public final class EmbedJwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Allowed clock skew when checking {@code exp}/{@code nbf} (seconds). */
    private static final long CLOCK_SKEW_SECONDS = 60;

    /** Minimum HS256 secret length. The security of HMAC-SHA256 rests on a key
     *  at least as long as the hash output (256 bits = 32 bytes); a shorter
     *  secret is brute-forceable offline. */
    static final int MIN_SECRET_BYTES = 32;

    /** A JWT is three base64url segments separated by dots. */
    static boolean looksLikeJwt(String token) {
        if (token == null) {
            return false;
        }
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) {
            return false;
        }
        int secondDot = token.indexOf('.', firstDot + 1);
        // exactly two dots, and a non-empty signature segment after the second
        return secondDot > firstDot && token.indexOf('.', secondDot + 1) < 0 && secondDot < token.length() - 1;
    }

    private EmbedJwt() {}

    /**
     * Verify a compact JWS (HS256) and return its validated claim set.
     *
     * @param compact the {@code header.payload.signature} token
     * @param secret the shared HS256 secret (UTF-8); must be non-empty
     * @param expectedAudience required {@code aud} value, or null/blank to skip the aud check
     * @param nowMillis current time (injectable for tests)
     * @return the verified payload claims
     * @throws EmbedJwtException on ANY validation failure (bad shape, wrong alg,
     *     bad signature, expired, not-yet-valid, wrong audience)
     */
    public static JsonNode verify(String compact, byte[] secret, String expectedAudience, long nowMillis)
            throws EmbedJwtException {
        if (compact == null || compact.isBlank()) {
            throw new EmbedJwtException("empty token");
        }
        if (secret == null || secret.length == 0) {
            // Misconfiguration: no secret means we cannot verify anything, so we
            // must reject rather than accept unsigned input. Fail closed.
            throw new EmbedJwtException("no signing secret configured");
        }
        if (secret.length < MIN_SECRET_BYTES) {
            // A short HS256 secret is brute-forceable offline — reject rather
            // than accept a weak-key signature (SEC + QA #1104 flag).
            throw new EmbedJwtException("signing secret too short (HS256 needs >= 32 bytes)");
        }
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new EmbedJwtException("malformed JWT (expected 3 non-empty segments)");
        }

        // 1. Header — pin alg=HS256 BEFORE any signature work (alg-confusion / none).
        JsonNode header = decodeJson(parts[0], "header");
        JsonNode alg = header.get("alg");
        if (alg == null || !"HS256".equals(alg.asText())) {
            throw new EmbedJwtException("unsupported or missing alg (only HS256 accepted)");
        }

        // 2. Signature — recompute over the exact signing input, constant-time compare.
        byte[] expected = hmacSha256((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8), secret);
        byte[] provided;
        try {
            provided = URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new EmbedJwtException("signature is not valid base64url");
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new EmbedJwtException("signature mismatch");
        }

        // 3. Claims — only after the signature is proven, so we never trust
        //    unverified bytes.
        JsonNode payload = decodeJson(parts[1], "payload");
        long nowSec = nowMillis / 1000L;

        JsonNode exp = payload.get("exp");
        if (exp == null || !exp.isNumber()) {
            throw new EmbedJwtException("missing exp (eternal tokens rejected)");
        }
        if (nowSec > exp.asLong() + CLOCK_SKEW_SECONDS) {
            throw new EmbedJwtException("token expired");
        }
        JsonNode nbf = payload.get("nbf");
        if (nbf != null && nbf.isNumber() && nowSec + CLOCK_SKEW_SECONDS < nbf.asLong()) {
            throw new EmbedJwtException("token not yet valid (nbf)");
        }
        if (expectedAudience != null && !expectedAudience.isBlank()) {
            if (!audienceMatches(payload.get("aud"), expectedAudience)) {
                throw new EmbedJwtException("audience mismatch");
            }
        }
        return payload;
    }

    /** {@code aud} may be a string or an array of strings (RFC 7519). */
    private static boolean audienceMatches(JsonNode aud, String expected) {
        if (aud == null) {
            return false;
        }
        if (aud.isTextual()) {
            return expected.equals(aud.asText());
        }
        if (aud.isArray()) {
            for (JsonNode n : aud) {
                if (n.isTextual() && expected.equals(n.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode decodeJson(String segment, String which) throws EmbedJwtException {
        try {
            return MAPPER.readTree(URL_DECODER.decode(segment));
        } catch (IllegalArgumentException e) {
            throw new EmbedJwtException(which + " is not valid base64url");
        } catch (Exception e) {
            throw new EmbedJwtException(which + " is not valid JSON");
        }
    }

    private static byte[] hmacSha256(byte[] data, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            // HmacSHA256 is JLS-mandated; an empty key is guarded above.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Test/encoder helper — base64url (no padding) for building signing input. */
    static String b64Url(byte[] bytes) {
        return URL_ENCODER.encodeToString(bytes);
    }

    /** Raised on any verification failure. Carries a developer-facing reason for
     *  server-side logging only — the HTTP response stays opaque. */
    public static final class EmbedJwtException extends Exception {
        public EmbedJwtException(String message) {
            super(message);
        }
    }
}
