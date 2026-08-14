/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
 * <p><b>Rate-limited</b> per client IP via {@link AiRateLimiter} to blunt brute-force / abuse.
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

    public void setConsentStore(RecipientConsentStore consentStore) {
        this.consentStore = consentStore;
    }

    /** Spring/test setter to override the per-IP rate limit. */
    public void setRateLimiter(AiRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
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
