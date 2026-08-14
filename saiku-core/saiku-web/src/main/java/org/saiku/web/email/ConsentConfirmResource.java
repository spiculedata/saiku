/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import org.saiku.service.mail.trust.RecipientConsentStore;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PUBLIC double-opt-in consent-confirm endpoint (saiku#1811, PR3). Mounted at
 * {@code /rest/saiku/mail/consent/confirm} and gated {@code permitAll} in Spring Security — a
 * recipient must be able to confirm their own consent from a mail client with no Saiku login.
 *
 * <p><b>Confirms only; sends nothing.</b> This endpoint flips a PENDING consent record to CONFIRMED
 * (via {@link RecipientConsentStore#confirm}); it can never enable a send by itself and it never mails
 * anyone. The anti-relay boundary (recipient is only ever the server's own {@code selfTo}) is
 * untouched.
 *
 * <p><b>Token-gated, no third-party self-confirm.</b> Confirmation requires the high-entropy random
 * token that was delivered (in a later PR) to the recipient's OWN mailbox; the store verifies it by
 * constant-time salted-hash match. An admin holding only the on-disk hash — or an attacker guessing —
 * cannot confirm an arbitrary third party.
 *
 * <p><b>No enumeration oracle.</b> The response is a GENERIC {@code 200} for every outcome — valid,
 * invalid / tampered / expired token, unknown address, or malformed input. The address is never echoed
 * and never logged (only a boolean whether a confirmation landed), so the endpoint reveals nothing
 * about which addresses exist or their consent state.
 *
 * <p><b>Rate-limited</b> per client IP via {@link AiRateLimiter} to blunt brute-force / abuse, AND
 * (saiku#1811 PR4, SEC carry-forward #2) per TARGET ADDRESS via a second limiter — so a distributed
 * attacker rotating source IPs still can't brute a single address's token past a low cap. The
 * per-address key is a salted hash of the normalised address (never the address itself), so the limiter
 * map holds no PII.
 */
@Path("/saiku/mail/consent/confirm")
public class ConsentConfirmResource {

    private static final Logger log = LoggerFactory.getLogger(ConsentConfirmResource.class);

    /** The single generic response body — identical for every outcome (no oracle). */
    private static final Map<String, String> GENERIC_OK =
            Map.of("status", "ok", "message", "If this confirmation link was valid, your consent has been recorded.");

    private RecipientConsentStore consentStore;

    @Context
    private HttpServletRequest request;

    /**
     * Per-IP rate limit for the public confirm endpoint. Direct {@code new} + Spring/test setter,
     * mirroring {@link UnsubscribeResource}. Keyed by client IP (no principal — unauthenticated).
     */
    private AiRateLimiter rateLimiter =
            new AiRateLimiter(Integer.getInteger("saiku.mail.consent.ratelimit.maxPerMinute", 20), 60_000L);

    /**
     * Per-TARGET-ADDRESS confirm cap (saiku#1811 PR4, SEC carry-forward #2). Defense-in-depth beyond the
     * per-IP limiter: caps confirm attempts against ONE address regardless of source IP, so an attacker
     * rotating IPs still can't brute that address's token. Default 10/min. Keyed by a salted hash of the
     * normalised address (never the address), so no PII lands in the limiter map.
     */
    private AiRateLimiter addressRateLimiter =
            new AiRateLimiter(Integer.getInteger("saiku.mail.consent.address.ratelimit.maxPerMinute", 10), 60_000L);

    /** Per-process random salt for the per-address limiter key — keeps the hash non-reversible/portable. */
    private static final byte[] ADDRESS_KEY_SALT = newSalt();

    public void setConsentStore(RecipientConsentStore consentStore) {
        this.consentStore = consentStore;
    }

    /** Spring/test setter to override the per-IP rate limit. */
    public void setRateLimiter(AiRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /** Spring/test setter to override the per-address confirm cap. */
    public void setAddressRateLimiter(AiRateLimiter addressRateLimiter) {
        this.addressRateLimiter = addressRateLimiter;
    }

    /** Test setter for the request (production injects via {@code @Context}). */
    void setRequest(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * Confirm consent. The recipient clicks a link carrying the token {@code t} and their address
     * {@code e}; on a valid, unexpired token the PENDING record flips to CONFIRMED (idempotent).
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response confirm(@QueryParam("t") String token, @QueryParam("e") String address) {
        if (!rateLimiter.tryAcquire(clientIp())) {
            // 429 is a transport signal, not an oracle about the address (we never looked at it first).
            return Response.status(429)
                    .entity(Map.of("status", "rate_limited", "message", "Too many requests. Please retry shortly."))
                    .build();
        }
        // SEC carry-forward #2: cap attempts against a single target address across ALL source IPs. The
        // key is a salted hash of the normalised address (never the address), and a null key (malformed
        // address) fails open on THIS limiter — the per-IP limiter above and the token check below still
        // apply, and there's no address to brute. Same generic 429 body (no oracle).
        if (!addressRateLimiter.tryAcquire(addressKey(address))) {
            return Response.status(429)
                    .entity(Map.of("status", "rate_limited", "message", "Too many requests. Please retry shortly."))
                    .build();
        }
        boolean confirmed = consentStore != null && consentStore.confirm(address, token);
        if (confirmed) {
            // Never log the address — only whether a confirmation landed.
            log.info("Consent confirm accepted");
        } else {
            log.debug("Consent confirm rejected (invalid/expired token or unknown address)");
        }
        // GENERIC 200 for valid AND invalid alike — never echo the address, never reveal the outcome.
        return Response.ok(GENERIC_OK).build();
    }

    /**
     * The per-address limiter key: a salted SHA-256 hash of the normalised address, base64-encoded.
     * Returns {@code null} for a malformed/blank address (then that limiter fails open — nothing to
     * brute). The address itself is NEVER used as the key, so no PII lands in the limiter map.
     */
    private static String addressKey(String address) {
        String normalised = normalise(address);
        if (normalised == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ADDRESS_KEY_SALT);
            md.update(normalised.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always present; if it somehow isn't, fail open on this belt-and-braces limiter.
            return null;
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
            InternetAddress ia = new InternetAddress(s, true);
            ia.validate();
            String bare = ia.getAddress();
            return bare == null ? null : bare.toLowerCase();
        } catch (AddressException e) {
            return null;
        }
    }

    private static byte[] newSalt() {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        return salt;
    }

    /** Client IP for the rate-limit key; {@code "unknown"} when no request is bound (tests). */
    private String clientIp() {
        try {
            String ip = request == null ? null : request.getRemoteAddr();
            return ip == null ? "unknown" : ip;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
