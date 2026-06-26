/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import org.saiku.web.security.embed.EmbedJwt.EmbedJwtException;

/**
 * saiku#1104 — adversarial coverage for the embed JWT verifier. The whole point
 * of this class is that forged / alg-confused / expired / mis-audienced tokens
 * are rejected, so the tests are mostly negative by design.
 */
public class EmbedJwtTest {

    private static final byte[] SECRET = "test-embed-secret-at-least-32-bytes-long!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG_SECRET =
            "a-totally-different-secret-key-32bytes-xx".getBytes(StandardCharsets.UTF_8);
    private static final String AUD = "saiku-embed";
    private static final long NOW = 1_750_000_000_000L; // fixed clock
    private static final long FUTURE = NOW / 1000L + 3600;
    private static final long PAST = NOW / 1000L - 3600;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static String seg(String json) {
        return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String signingInput, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Mint a compact JWT signed (HS256) over header.payload with {@code secret}. */
    private static String mint(String headerJson, String payloadJson, byte[] secret) {
        String h = seg(headerJson);
        String p = seg(payloadJson);
        return h + "." + p + "." + hmac(h + "." + p, secret);
    }

    private static String validPayload() {
        return "{\"sub\":\"u_1234\",\"tenantId\":\"acme\",\"saiku.cube\":\"Sales\",\"aud\":\"" + AUD
                + "\",\"exp\":" + FUTURE
                + ",\"saiku.filters\":[{\"dim\":\"[Customer].[Customer]\",\"op\":\"in\",\"values\":[\"acme\"]}]}";
    }

    private static final String HS256_HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private static void assertRejected(String compact, String why) {
        try {
            EmbedJwt.verify(compact, SECRET, AUD, NOW);
            fail("expected rejection: " + why);
        } catch (EmbedJwtException expected) {
            // good
        }
    }

    @Test
    public void valid_token_verifies_and_exposes_claims() throws Exception {
        JsonNode claims = EmbedJwt.verify(mint(HS256_HEADER, validPayload(), SECRET), SECRET, AUD, NOW);
        assertEquals("u_1234", claims.get("sub").asText());
        assertEquals("acme", claims.get("tenantId").asText());
        assertEquals("Sales", claims.get("saiku.cube").asText());
        assertTrue(claims.get("saiku.filters").isArray());
        assertEquals(
                "[Customer].[Customer]",
                claims.get("saiku.filters").get(0).get("dim").asText());
    }

    @Test
    public void alg_none_is_rejected() {
        // classic JWT bypass: alg:none with an empty/garbage signature
        String compact = seg("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + seg(validPayload()) + ".";
        assertRejected(compact, "alg:none");
        // even with a non-empty sig segment, alg:none must die at the header check
        assertRejected(seg("{\"alg\":\"none\"}") + "." + seg(validPayload()) + ".AAAA", "alg:none w/ sig");
    }

    @Test
    public void alg_confusion_other_algs_rejected() {
        assertRejected(mint("{\"alg\":\"HS512\"}", validPayload(), SECRET), "HS512");
        assertRejected(mint("{\"alg\":\"RS256\"}", validPayload(), SECRET), "RS256");
        assertRejected(mint("{\"typ\":\"JWT\"}", validPayload(), SECRET), "missing alg");
    }

    @Test
    public void wrong_secret_is_rejected() {
        assertRejected(mint(HS256_HEADER, validPayload(), WRONG_SECRET), "signed with wrong secret");
    }

    @Test
    public void tampered_payload_is_rejected() {
        // sign a benign payload, then swap in an escalated one (sig no longer matches)
        String benign = mint(HS256_HEADER, validPayload(), SECRET);
        String[] parts = benign.split("\\.");
        String evil = parts[0] + "." + seg(validPayload().replace("acme", "victim-corp")) + "." + parts[2];
        assertRejected(evil, "tampered payload");
    }

    @Test
    public void expired_token_is_rejected() {
        String p = "{\"sub\":\"u\",\"aud\":\"" + AUD + "\",\"exp\":" + PAST + "}";
        assertRejected(mint(HS256_HEADER, p, SECRET), "expired");
    }

    @Test
    public void missing_exp_is_rejected() {
        String p = "{\"sub\":\"u\",\"aud\":\"" + AUD + "\"}";
        assertRejected(mint(HS256_HEADER, p, SECRET), "no exp (eternal)");
    }

    @Test
    public void not_yet_valid_nbf_is_rejected() {
        String p = "{\"sub\":\"u\",\"aud\":\"" + AUD + "\",\"exp\":" + FUTURE + ",\"nbf\":" + FUTURE + "}";
        assertRejected(mint(HS256_HEADER, p, SECRET), "nbf in future");
    }

    @Test
    public void wrong_audience_is_rejected() {
        String p = "{\"sub\":\"u\",\"aud\":\"some-other-app\",\"exp\":" + FUTURE + "}";
        assertRejected(mint(HS256_HEADER, p, SECRET), "aud mismatch");
    }

    @Test
    public void audience_as_array_is_accepted() throws Exception {
        String p = "{\"sub\":\"u\",\"aud\":[\"x\",\"" + AUD + "\"],\"exp\":" + FUTURE + "}";
        JsonNode claims = EmbedJwt.verify(mint(HS256_HEADER, p, SECRET), SECRET, AUD, NOW);
        assertEquals("u", claims.get("sub").asText());
    }

    @Test
    public void no_expected_audience_skips_aud_check() throws Exception {
        String p = "{\"sub\":\"u\",\"exp\":" + FUTURE + "}";
        JsonNode claims = EmbedJwt.verify(mint(HS256_HEADER, p, SECRET), SECRET, null, NOW);
        assertEquals("u", claims.get("sub").asText());
    }

    @Test
    public void malformed_shapes_are_rejected() {
        assertRejected("only.two", "two segments");
        assertRejected("a.b.c.d", "four segments");
        assertRejected("..", "empty segments");
        assertRejected(seg(HS256_HEADER) + "." + seg(validPayload()) + ".", "empty signature");
        assertRejected("!!!." + seg(validPayload()) + ".AAAA", "non-base64 header");
        assertRejected(seg("not json") + "." + seg(validPayload()) + ".AAAA", "header not json");
    }

    @Test
    public void empty_secret_is_rejected() {
        try {
            EmbedJwt.verify(mint(HS256_HEADER, validPayload(), SECRET), new byte[0], AUD, NOW);
            fail("no secret must fail closed");
        } catch (EmbedJwtException expected) {
            // good
        }
    }

    @Test
    public void short_secret_is_rejected() {
        // A < 32-byte secret is brute-forceable offline — even a token correctly
        // signed with that weak secret must be refused (SEC + QA #1104 flag).
        byte[] weak = "too-short-31-bytes-secret-aaaaa".getBytes(StandardCharsets.UTF_8); // 31 bytes
        String token = mint(HS256_HEADER, validPayload(), weak);
        try {
            EmbedJwt.verify(token, weak, AUD, NOW);
            fail("a sub-32-byte secret must fail closed");
        } catch (EmbedJwtException expected) {
            // good
        }
    }

    @Test
    public void exp_as_string_is_rejected() {
        // Claim-type confusion: exp must be numeric; a string exp is not a valid
        // expiry and must be treated as missing -> rejected (locks isNumber()).
        String p = "{\"sub\":\"u\",\"aud\":\"" + AUD + "\",\"exp\":\"" + FUTURE + "\"}";
        assertRejected(mint(HS256_HEADER, p, SECRET), "exp as string");
    }

    @Test
    public void alg_as_number_is_rejected() {
        // alg must be the textual "HS256"; a numeric alg must not slip past the
        // header check (locks the asText()/equals guard).
        assertRejected(mint("{\"alg\":256}", validPayload(), SECRET), "alg as number");
    }

    @Test
    public void looksLikeJwt_distinguishes_jwt_from_opaque() {
        assertTrue(EmbedJwt.looksLikeJwt("aaa.bbb.ccc"));
        assertFalse("opaque UUID token", EmbedJwt.looksLikeJwt("8f3a2b1c-1234-5678-9abc-def012345678"));
        assertFalse(EmbedJwt.looksLikeJwt("only.two"));
        assertFalse(EmbedJwt.looksLikeJwt("trailing.dot."));
        assertFalse(EmbedJwt.looksLikeJwt(null));
    }
}
